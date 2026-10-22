import {HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {DatePipe} from '@angular/common';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {combineLatest, firstValueFrom, from, Subscription, timeout} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
import {
  ApprovalCenterService,
  groups,
  Group,
  ApprovalPage,
  Approval,
  validApprovals,
} from './approval-center.service';

@Component({
  selector: 'app-approval-list-page',
  standalone: true,
  imports: [DatePipe, RouterLink],
  templateUrl: './approval-list-page.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './approval-list-page.component.css',
  ],
})
export class ApprovalListPageComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApprovalCenterService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  id = '';
  page = 0;
  group: Group = 'PENDING';
  readonly groups = groups;
  readonly expanded = signal<string | null>(null);
  readonly context = signal<Workspace | null>(null);
  readonly result = signal<ApprovalPage | null>(null);
  readonly loading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly menuOpen = signal(false);
  constructor() {
    effect(() => {
      const account = this.auth.user()?.id;
      if (account !== this.account) {
        this.account = account;
        this.clear();
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => this.clear());
    combineLatest([this.route.paramMap, this.route.queryParamMap])
      .pipe(takeUntilDestroyed())
      .subscribe(([params, query]) => {
        this.id = params.get('id') || '';
        const raw = query.get('page') || '0';
        this.page = /^\d+$/.test(raw) ? Number(raw) : -1;
        this.group = (query.get('group') || 'PENDING') as Group;
        void this.load();
      });
  }
  private clear(): void {
    this.expanded.set(null);
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.result.set(null);
    this.context.set(null);
    this.loading.set(false);
    this.error.set('');
    this.closeMenu();
  }
  async load(): Promise<void> {
    this.clear();
    this.denied.set(false);
    if (
      !uuid(this.id) ||
      !Number.isInteger(this.page) ||
      this.page < 0 ||
      this.page > 100000 ||
      !this.groups.includes(this.group)
    ) {
      this.error.set('Invalid approval filters. Clear filters to continue.');
      return;
    }
    const epoch = this.epoch;
    const account = this.auth.user()?.id;
    const active = () =>
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      account === this.auth.user()?.id;
    this.loading.set(true);
    try {
      const context = await firstValueFrom(
        from(this.auth.getWorkspace(this.id)).pipe(timeout(30000)),
      );
      if (!active()) return;
      if (
        !account ||
        context.id !== this.id ||
        !['ADMIN', 'MEMBER'].includes(context.role)
      ) {
        this.clear();
        this.denied.set(true);
        return;
      }
      this.context.set(context);
      this.requests.add(
        this.api.list(this.id, this.page, this.group).subscribe({
          next: response => {
            if (!active()) return;
            this.loading.set(false);
            if (
              !validApprovals(response?.data, this.id, this.page, this.group)
            ) {
              this.error.set(
                'Invalid approval response. Try again to load current data.',
              );
              return;
            }
            this.result.set(response.data);
          },
          error: error => {
            if (active()) this.failure(error);
          },
        }),
      );
    } catch (error) {
      if (active()) this.failure(error);
    }
  }
  private failure(error: unknown): void {
    this.result.set(null);
    this.context.set(null);
    this.loading.set(false);
    if (
      (error instanceof HttpErrorResponse &&
        [401, 403, 404].includes(error.status)) ||
      (error instanceof AuthFailure &&
        ['unauthorized', 'not-found'].includes(error.kind))
    ) {
      this.denied.set(true);
    } else this.error.set('Unable to load approvals. Try again.');
  }
  selectGroup(group: Group): void {
    this.navigate(0, group);
  }
  clearFilters(): void {
    this.navigate(0, 'PENDING');
  }
  changePage(page: number): void {
    this.navigate(page, this.group);
  }
  private navigate(page: number, group: Group): void {
    const target = this.router.createUrlTree(
      ['/workspace', this.id, 'approvals'],
      {queryParams: {group, ...(page ? {page} : {})}},
    );
    if (this.router.serializeUrl(target) === this.router.url) void this.load();
    else void this.router.navigateByUrl(target);
  }
  label(group: Group): string {
    return group === 'PENDING'
      ? 'Pending'
      : group === 'NEEDS_INFORMATION'
        ? 'Needs information'
        : 'Decided';
  }
  count(group: Group): number | null {
    const counts = this.result()?.counts;
    return counts
      ? group === 'PENDING'
        ? counts.pending
        : group === 'NEEDS_INFORMATION'
          ? counts.needsInformation
          : counts.decided
      : null;
  }
  age(item: Approval): string {
    const observed = this.result()?.generatedAt;
    if (!observed) return 'Unavailable';
    const minutes = Math.floor(
      (Date.parse(observed) - Date.parse(item.createdAt)) / 60000,
    );
    if (minutes < 0) return 'Future timestamp';
    return minutes < 60
      ? `${minutes}m`
      : minutes < 1440
        ? `${Math.floor(minutes / 60)}h`
        : `${Math.floor(minutes / 1440)}d`;
  }
  toggleMenu(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#approval-menu')
      ?.showModal();
  }
  closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#approval-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="approval-menu"]')
      ?.focus();
  }
}

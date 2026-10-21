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
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {combineLatest, Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {uuid} from './team.service';
import {
  TeamDirectoryService,
  teamStatuses,
  DirectoryPage,
  validDirectory,
} from './team-directory.service';

@Component({
  selector: 'app-team-directory',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './team-directory.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './team-directory.component.css',
  ],
})
export class TeamDirectoryComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(TeamDirectoryService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  id = '';
  page = 0;
  query = '';
  status = '';
  search = '';
  selectedStatus = '';
  readonly statuses = teamStatuses;
  readonly context = signal<Workspace | null>(null);
  readonly result = signal<DirectoryPage | null>(null);
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
        this.query = query.get('query') || '';
        this.status = query.get('status') || '';
        this.search = this.query;
        this.selectedStatus = this.status;
        void this.load();
      });
  }
  private clear(): void {
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
      this.query.length > 200 ||
      (this.status && !this.statuses.some(status => status === this.status))
    ) {
      this.error.set('Invalid directory filters. Clear filters to continue.');
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
      const context = await this.auth.getWorkspace(this.id);
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
        this.api.list(this.id, this.page, this.query, this.status).subscribe({
          next: response => {
            if (!active()) return;
            this.loading.set(false);
            if (!validDirectory(response?.data, this.id, this.page)) {
              this.error.set(
                'Invalid directory response. Try again to load current data.',
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
    } else this.error.set('Không thể tải danh sách Team. Vui lòng thử lại.');
  }
  apply(): void {
    this.navigate(0, this.search.trim(), this.selectedStatus);
  }
  clearFilters(): void {
    this.navigate(0, '', '');
  }
  changePage(page: number): void {
    this.navigate(page, this.query, this.status);
  }
  private navigate(page: number, query: string, status: string): void {
    const target = this.router.createUrlTree(['/workspace', this.id, 'teams'], {
      queryParams: {
        ...(page ? {page} : {}),
        ...(query ? {query} : {}),
        ...(status ? {status} : {}),
      },
    });
    if (this.router.serializeUrl(target) === this.router.url) void this.load();
    else void this.router.navigateByUrl(target);
  }
  toggleMenu(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#directory-menu')
      ?.showModal();
  }
  closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#directory-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="directory-menu"]')
      ?.focus();
  }
}

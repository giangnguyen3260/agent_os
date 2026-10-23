import {DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {Component, DestroyRef, effect, inject, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {combineLatest, firstValueFrom, from, Subscription, timeout} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
import {
  GrantPage,
  GrantStatus,
  grantStatuses,
  MemberAccessService,
  MemberPage,
  stripMemberQuery,
  validGrants,
  validMembers,
} from './member-access.service';

@Component({
  selector: 'app-member-access',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './member-access.component.html',
  styleUrl: './member-access.component.css',
})
export class MemberAccessComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(MemberAccessService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private requests = new Subscription();
  private epoch = 0;
  private actor = this.auth.user()?.id;
  id = '';
  accountId = '';
  page = 0;
  query = '';
  role = '';
  status: GrantStatus = 'ALL';
  searchInput = '';
  roleInput = '';
  statusInput: GrantStatus = 'ALL';
  readonly statuses = grantStatuses;
  readonly context = signal<Workspace | null>(null);
  readonly members = signal<MemberPage | null>(null);
  readonly grants = signal<GrantPage | null>(null);
  readonly loading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  constructor() {
    effect(() => {
      const actor = this.auth.user()?.id;
      if (actor !== this.actor) {
        this.actor = actor;
        this.clear();
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => this.clear());
    combineLatest([this.route.paramMap, this.route.queryParamMap])
      .pipe(takeUntilDestroyed())
      .subscribe(([params, query]) => {
        this.id = params.get('id') || '';
        this.accountId = params.get('accountId') || '';
        const raw = query.get('page') || '0';
        this.page = /^\d+$/.test(raw) ? Number(raw) : -1;
        this.query = stripMemberQuery(query.get('query') || '');
        this.role = query.get('role') || '';
        this.status = (query.get('status') || 'ALL') as GrantStatus;
        void this.load();
      });
  }
  private clear(): void {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.context.set(null);
    this.members.set(null);
    this.grants.set(null);
    this.loading.set(false);
    this.error.set('');
    this.searchInput = '';
    this.roleInput = '';
    this.statusInput = 'ALL';
  }
  async load(): Promise<void> {
    this.clear();
    this.denied.set(false);
    if (
      !uuid(this.id) ||
      (this.accountId && !uuid(this.accountId)) ||
      !Number.isInteger(this.page) ||
      this.page < 0 ||
      this.page > 100000 ||
      [...this.query].length > 200 ||
      !['', 'ADMIN', 'MEMBER'].includes(this.role) ||
      !this.statuses.includes(this.status)
    ) {
      this.error.set('Bộ lọc không hợp lệ. Xóa bộ lọc để tiếp tục.');
      return;
    }
    const epoch = this.epoch;
    const actor = this.auth.user()?.id;
    const active = () =>
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      actor === this.auth.user()?.id;
    this.loading.set(true);
    try {
      const context = await firstValueFrom(
        from(this.auth.getWorkspace(this.id)).pipe(timeout(30000)),
      );
      if (!active()) return;
      if (!actor || context?.id !== this.id || context.role !== 'ADMIN') {
        this.clear();
        this.denied.set(true);
        return;
      }
      const operation = this.accountId
        ? this.api.grants(this.id, this.accountId, this.page, this.status)
        : this.api.members(this.id, this.page, this.query, this.role);
      this.requests.add(
        operation.subscribe({
          next: response => {
            if (!active()) return;
            const value = response?.data;
            if (this.accountId) {
              if (
                !validGrants(
                  value,
                  this.id,
                  actor,
                  this.accountId,
                  this.page,
                  this.status,
                )
              ) {
                this.failure(null);
                return;
              }
              this.grants.set(value);
            } else {
              if (!validMembers(value, this.id, actor, this.page, this.role)) {
                this.failure(null);
                return;
              }
              this.members.set(value);
            }
            this.context.set(context);
            this.searchInput = this.query;
            this.roleInput = this.role;
            this.statusInput = this.status;
            this.loading.set(false);
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
    this.clear();
    if (
      (error instanceof HttpErrorResponse &&
        [401, 403, 404].includes(error.status)) ||
      (error instanceof AuthFailure &&
        ['unauthorized', 'not-found'].includes(error.kind))
    )
      this.denied.set(true);
    else this.error.set('Không thể tải thành viên và quyền. Vui lòng thử lại.');
  }
  apply(): void {
    this.navigate(
      0,
      stripMemberQuery(this.searchInput),
      this.roleInput,
      this.statusInput,
    );
  }
  clearFilters(): void {
    this.navigate(0, '', '', 'ALL');
  }
  changePage(page: number): void {
    this.navigate(page, this.query, this.role, this.status);
  }
  private navigate(
    page: number,
    query: string,
    role: string,
    status: GrantStatus,
  ): void {
    const path = ['/workspace', this.id, 'members'];
    if (this.accountId) path.push(this.accountId, 'approval-grants');
    const queryParams = this.accountId
      ? {...(status !== 'ALL' ? {status} : {}), ...(page ? {page} : {})}
      : {
          ...(query ? {query} : {}),
          ...(role ? {role} : {}),
          ...(page ? {page} : {}),
        };
    const target = this.router.createUrlTree(path, {queryParams});
    if (this.router.serializeUrl(target) === this.router.url) void this.load();
    else void this.router.navigateByUrl(target);
  }
  statusLabel(status: string): string {
    return status === 'ALL'
      ? 'Tất cả'
      : status === 'UNEXPIRED'
        ? 'Chưa hết hạn'
        : status === 'EXPIRED'
          ? 'Đã hết hạn'
          : 'Đã thu hồi';
  }
  roleLabel(role: string): string {
    return role === 'ADMIN' ? 'Quản trị viên tổ chức' : 'Thành viên tổ chức';
  }
}

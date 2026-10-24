import {HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  HostListener,
  effect,
  inject,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Subscription, firstValueFrom, from, timeout} from 'rxjs';
import {AuthService, Workspace} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
import {MemberRoleNavigationState} from './member-role-navigation.guard';
import {
  Command,
  Context,
  Correlation,
  History,
  MemberRoleManagementService,
  Result,
  Role,
  exact,
  reasonText,
  role,
  validContext,
  validHistory,
  validReason,
  validResult,
} from './member-role-management.service';
interface Pending extends Correlation {
  body: Command;
  ambiguous: boolean;
}
@Component({
  selector: 'app-member-role-management',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './member-role-management.component.html',
  styleUrls: [
    './member-access.component.css',
    './grant-management.component.css',
    './member-role-management.component.css',
  ],
})
export class MemberRoleManagementComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(MemberRoleManagementService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly navigation = inject(MemberRoleNavigationState);
  private readonly destroy = inject(DestroyRef);
  private requests = new Subscription();
  private epoch = 0;
  private historyEpoch = 0;
  private actor = this.auth.user()?.id;
  private allowedNavigation = 0;
  private pending: Pending | null = null;
  private unresolved: Correlation | null = null;
  id = '';
  accountId = '';
  newRole = '';
  reason = '';
  acknowledgement = false;
  historyPage = 0;
  readonly workspace = signal<Workspace | null>(null);
  readonly context = signal<Context | null>(null);
  readonly history = signal<History | null>(null);
  readonly result = signal<Result | null>(null);
  readonly stage = signal<'edit' | 'review'>('edit');
  readonly identityChecking = signal(false);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly historyLoading = signal(false);
  readonly denied = signal(false);
  readonly unknown = signal(false);
  readonly notice = signal('');
  readonly error = signal('');
  constructor() {
    const check = (org: string, account: string) =>
      (org === this.id && account === this.accountId) || this.canLeave();
    this.navigation.check = check;
    this.destroy.onDestroy(() => {
      this.reset();
      if (this.navigation.check === check) this.navigation.check = null;
    });
    effect(() => {
      const actor = this.auth.user()?.id;
      if (actor !== this.actor) {
        this.actor = actor;
        this.reset();
        this.denied.set(true);
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.reset();
      this.id = params.get('id') || '';
      this.accountId = params.get('accountId') || '';
      void this.load();
    });
  }
  private active(epoch: number, actor: string | undefined) {
    return (
      !this.destroy.destroyed &&
      !this.identityChecking() &&
      epoch === this.epoch &&
      actor === this.auth.user()?.id
    );
  }
  private clearPrivate() {
    this.epoch++;
    this.historyEpoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.workspace.set(null);
    this.context.set(null);
    this.history.set(null);
    this.result.set(null);
    this.loading.set(false);
    this.busy.set(false);
    this.historyLoading.set(false);
    this.error.set('');
    this.newRole = '';
    this.reason = '';
    this.acknowledgement = false;
    this.historyPage = 0;
    this.stage.set('edit');
  }
  private reset() {
    this.clearPrivate();
    this.pending = null;
    this.unresolved = null;
    this.unknown.set(false);
    this.denied.set(false);
    this.notice.set('');
  }
  private correlation() {
    return this.pending || this.unresolved;
  }
  private opaque(p: Correlation): Correlation {
    return {
      org: p.org,
      account: p.account,
      actor: p.actor,
      key: p.key,
      operation: p.operation,
      revision: p.revision,
      beforeRole: p.beforeRole,
      afterRole: p.afterRole,
    };
  }
  private failure(e: unknown) {
    const p = this.correlation();
    this.unresolved = p ? this.opaque(p) : null;
    this.pending = null;
    this.clearPrivate();
    this.unknown.set(!!p);
    this.denied.set(
      e instanceof HttpErrorResponse && [401, 403, 404].includes(e.status),
    );
    if (p)
      this.notice.set(
        'Kết quả cũ vẫn chưa xác định. Nội dung riêng tư đã xóa; chỉ kiểm tra biên nhận chính xác khi có lại quyền.',
      );
    else
      this.error.set('Không thể xác thực dữ liệu vai trò. Vui lòng tải lại.');
  }
  private async authorize(epoch: number, actor: string | undefined) {
    const org = this.id;
    const w = (await firstValueFrom(this.api.workspace(org)))?.data;
    if (!this.active(epoch, actor)) return null;
    if (
      !actor ||
      !exact(w, 'id name role') ||
      w?.id !== org ||
      w.role !== 'ADMIN' ||
      typeof w.name !== 'string' ||
      !w.name.trim() ||
      [...w.name].length > 255
    ) {
      this.failure(new HttpErrorResponse({status: 403}));
      return null;
    }
    return w;
  }
  async load() {
    if (this.busy() || this.identityChecking()) return;
    this.clearPrivate();
    this.denied.set(false);
    if (!uuid(this.id) || !uuid(this.accountId)) {
      this.error.set('Địa chỉ thành viên không hợp lệ.');
      return;
    }
    const epoch = this.epoch,
      actor = this.auth.user()?.id;
    this.loading.set(true);
    try {
      const w = await this.authorize(epoch, actor);
      if (!w) return;
      const p = this.correlation();
      if (this.unknown() && p) {
        const r = (
          await firstValueFrom(this.api.receipt(p.org, p.account, p.key))
        )?.data;
        if (!this.active(epoch, actor)) return;
        if (r !== null) {
          if (!this.accept(r, p)) this.failure(null);
          else this.workspace.set(w);
          return;
        }
        this.workspace.set(w);
        this.loading.set(false);
        this.notice.set(
          'Chưa tìm thấy biên nhận chính xác. Kết quả vẫn chưa xác định; không tạo thao tác thay thế.',
        );
        return;
      }
      const r = (
        await firstValueFrom(this.api.context(this.id, this.accountId))
      )?.data;
      if (!this.active(epoch, actor)) return;
      if (!validContext(r, this.id, this.accountId, actor!)) {
        this.failure(null);
        return;
      }
      this.workspace.set(w);
      this.context.set(r);
      this.loading.set(false);
      void this.loadHistory(0);
    } catch (e) {
      if (this.active(epoch, actor)) {
        if (
          e instanceof HttpErrorResponse &&
          e.status === 404 &&
          !this.correlation()
        ) {
          this.clearPrivate();
          void this.loadHistory(0);
        } else this.failure(e);
      }
    }
  }
  changed() {
    this.acknowledgement = false;
    this.stage.set('edit');
  }
  canReview() {
    const c = this.context();
    return (
      !this.identityChecking() &&
      !this.loading() &&
      !this.busy() &&
      !this.unknown() &&
      !this.result() &&
      !!c &&
      !c.member.isSelf &&
      c.member.enabled &&
      role(this.newRole) &&
      c.member.currentRole !== this.newRole &&
      validReason(this.reason)
    );
  }
  async review() {
    if (!this.canReview() || !role(this.newRole)) return;
    const selected = this.newRole,
      reason = reasonText(this.reason),
      old = this.context()?.revision;
    this.clearPrivate();
    this.busy.set(true);
    const epoch = this.epoch,
      actor = this.auth.user()?.id;
    try {
      const w = await this.authorize(epoch, actor);
      if (!w) return;
      const c = (
        await firstValueFrom(
          this.api.context(this.id, this.accountId, selected),
        )
      )?.data;
      if (!this.active(epoch, actor)) return;
      if (!validContext(c, this.id, this.accountId, actor!, selected)) {
        this.failure(null);
        return;
      }
      this.workspace.set(w);
      this.context.set(c);
      this.newRole = selected;
      this.reason = reason;
      this.busy.set(false);
      this.acknowledgement = false;
      if (c.canChange && c.revision < 2147483647) this.stage.set('review');
      if (old !== c.revision)
        this.notice.set(
          'Dữ liệu đã thay đổi. Hãy xem lại phiên bản mới trước khi xác nhận.',
        );
      void this.loadHistory(0);
    } catch (e) {
      if (this.active(epoch, actor)) this.failure(e);
    }
  }
  edit() {
    if (this.busy() || this.unknown() || this.identityChecking()) return;
    this.stage.set('edit');
    this.acknowledgement = false;
  }
  send() {
    const c = this.context(),
      actor = this.auth.user()?.id;
    if (
      !this.canReview() ||
      this.stage() !== 'review' ||
      !this.acknowledgement ||
      !actor ||
      !c?.canChange ||
      c.selectedRole !== this.newRole ||
      !role(this.newRole) ||
      c.revision >= 2147483647 ||
      this.correlation()
    )
      return;
    this.pending = {
      org: this.id,
      account: this.accountId,
      actor,
      key: crypto.randomUUID(),
      operation: 'CHANGE_ROLE',
      revision: c.revision,
      beforeRole: c.member.currentRole,
      afterRole: this.newRole,
      body: Object.freeze({
        expectedRevision: c.revision,
        expectedCurrentRole: c.member.currentRole,
        newRole: this.newRole,
        reason: reasonText(this.reason),
        acknowledgement: true,
      }),
      ambiguous: false,
    };
    void this.transmit();
  }
  canRetry() {
    const p = this.pending;
    return (
      !this.identityChecking() &&
      !this.busy() &&
      !this.loading() &&
      this.unknown() &&
      !this.denied() &&
      !!this.workspace() &&
      !!p &&
      p.actor === this.auth.user()?.id &&
      p.org === this.id &&
      p.account === this.accountId
    );
  }
  retry() {
    if (this.canRetry()) void this.transmit();
  }
  private accept(v: unknown, p: Correlation) {
    if (!validResult(v, p)) return false;
    this.pending = null;
    this.unresolved = null;
    this.unknown.set(false);
    this.reason = '';
    this.acknowledgement = false;
    this.context.set(null);
    this.result.set(v);
    this.loading.set(false);
    this.busy.set(false);
    this.notice.set(
      'Biên nhận là lịch sử thao tác. Làm mới thành viên để xem vai trò hiện tại.',
    );
    return true;
  }
  private async transmit() {
    const p = this.pending;
    if (
      !p ||
      this.busy() ||
      this.identityChecking() ||
      this.denied() ||
      p.actor !== this.auth.user()?.id ||
      p.org !== this.id ||
      p.account !== this.accountId
    )
      return;
    const epoch = this.epoch,
      actor = p.actor;
    this.busy.set(true);
    this.error.set('');
    try {
      const w = await this.authorize(epoch, actor);
      if (!w || this.pending !== p) return;
      this.requests.add(
        this.api.send(p.org, p.account, p.key, p.body).subscribe({
          next: r => {
            if (!this.active(epoch, actor)) return;
            this.busy.set(false);
            if (!this.accept(r?.data, p)) {
              p.ambiguous = true;
              this.unknown.set(true);
              this.notice.set(
                'Không thể xác thực phản hồi. Kết quả chưa xác định; kiểm tra biên nhận hoặc gửi lại đúng yêu cầu này.',
              );
            }
          },
          error: e => {
            if (!this.active(epoch, actor)) return;
            this.busy.set(false);
            if (
              e instanceof HttpErrorResponse &&
              [401, 403, 404].includes(e.status)
            ) {
              this.failure(e);
              return;
            }
            if (
              e instanceof HttpErrorResponse &&
              [400, 409].includes(e.status)
            ) {
              if (p.ambiguous) {
                this.failure(e);
                return;
              }
              this.pending = null;
              this.clearPrivate();
              this.error.set(
                'Thay đổi bị từ chối. Tải lại và xem lại dữ liệu mới trước khi gửi.',
              );
              return;
            }
            p.ambiguous = true;
            this.unknown.set(true);
            this.notice.set(
              'Kết quả chưa xác định. Không tạo thao tác thay thế; kiểm tra biên nhận hoặc gửi lại đúng yêu cầu này.',
            );
          },
        }),
      );
    } catch (e) {
      if (this.active(epoch, actor)) this.failure(e);
    }
  }
  async loadHistory(page: number) {
    if (
      this.identityChecking() ||
      this.busy() ||
      this.unknown() ||
      page < 0 ||
      page > 100000 ||
      !Number.isInteger(page)
    )
      return;
    const epoch = this.epoch,
      actor = this.auth.user()?.id,
      version = ++this.historyEpoch;
    this.history.set(null);
    this.historyLoading.set(true);
    try {
      const w = await this.authorize(epoch, actor);
      if (!w || version !== this.historyEpoch) return;
      const h = (
        await firstValueFrom(this.api.history(this.id, this.accountId, page))
      )?.data;
      if (!this.active(epoch, actor) || version !== this.historyEpoch) return;
      if (!validHistory(h, this.id, this.accountId, page)) {
        this.failure(null);
        return;
      }
      this.workspace.set(w);
      this.denied.set(false);
      this.history.set(h);
      this.historyPage = page;
      this.historyLoading.set(false);
    } catch (e) {
      if (this.active(epoch, actor) && version === this.historyEpoch)
        this.failure(e);
    }
  }
  roleLabel(r: Role) {
    return r === 'ADMIN' ? 'Quản trị viên tổ chức' : 'Thành viên tổ chức';
  }
  blocker(code: string) {
    return (
      (
        {
          SELF_CHANGE: 'Không được tự thay đổi vai trò của mình.',
          TARGET_DISABLED: 'Tài khoản đã bị vô hiệu hóa.',
          NO_CHANGE: 'Vai trò mới trùng vai trò hiện tại.',
          LAST_ENABLED_ADMIN:
            'Cần giữ ít nhất một quản trị viên đang hoạt động.',
        } as Record<string, string>
      )[code] || code
    );
  }
  @HostListener('window:focus', ['$event']) onFocus(e: FocusEvent) {
    if (e.target !== window) return;
    void this.revalidateIdentity();
  }
  @HostListener('document:visibilitychange') onVisibility() {
    if (document.visibilityState === 'visible') void this.revalidateIdentity();
  }
  async revalidateIdentity() {
    if (this.identityChecking() || this.destroy.destroyed) return;
    this.identityChecking.set(true);
    const actor = this.auth.user()?.id,
      org = this.id,
      account = this.accountId,
      p = this.pending;
    const interrupted = this.loading() || this.busy() || this.historyLoading();
    if (p) {
      p.ambiguous = true;
      this.clearPrivate();
      this.unknown.set(true);
    }
    const epoch = this.epoch;
    try {
      const user = await firstValueFrom(
        from(this.auth.session()).pipe(timeout(30000)),
      );
      if (this.destroy.destroyed) return;
      if (user.id !== actor || this.auth.user()?.id !== actor) {
        this.identityChecking.set(false);
        this.reset();
        this.denied.set(true);
        return;
      }
      if (org !== this.id || account !== this.accountId) {
        this.identityChecking.set(false);
        void this.load();
        return;
      }
      const w = (await firstValueFrom(this.api.workspace(org)))?.data;
      if (this.destroy.destroyed) return;
      if (this.auth.user()?.id !== actor) {
        this.identityChecking.set(false);
        this.reset();
        this.denied.set(true);
        return;
      }
      if (org !== this.id || account !== this.accountId) {
        this.identityChecking.set(false);
        void this.load();
        return;
      }
      this.identityChecking.set(false);
      if (
        !exact(w, 'id name role') ||
        w?.id !== org ||
        w.role !== 'ADMIN' ||
        typeof w.name !== 'string' ||
        !w.name.trim()
      ) {
        this.failure(new HttpErrorResponse({status: 403}));
        return;
      }
      if (p || interrupted || epoch !== this.epoch) {
        this.clearPrivate();
        void this.load();
      }
    } catch (e) {
      this.identityChecking.set(false);
      if (this.destroy.destroyed) return;
      if (actor !== this.auth.user()?.id) {
        this.reset();
        this.denied.set(true);
      } else if (org !== this.id || account !== this.accountId)
        void this.load();
      else this.failure(e);
    }
  }
  private dirty() {
    return (
      this.busy() ||
      this.unknown() ||
      !!this.correlation() ||
      this.stage() === 'review' ||
      !!this.newRole ||
      !!reasonText(this.reason)
    );
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(
    e: BeforeUnloadEvent,
  ) {
    if (!this.result() && this.dirty()) e.preventDefault();
  }
  canLeave() {
    if (this.result() || !this.dirty()) return true;
    const id = this.router.getCurrentNavigation()?.id;
    if (id && id === this.allowedNavigation) return true;
    const leave = window.confirm(
      this.unknown() || !!this.correlation()
        ? 'Kết quả thao tác chưa xác định. Rời trang có thể làm mất khóa kiểm tra biên nhận. Bạn vẫn muốn rời trang?'
        : 'Rời trang và bỏ thay đổi vai trò chưa lưu?',
    );
    if (leave && id) this.allowedNavigation = id;
    return leave;
  }
}

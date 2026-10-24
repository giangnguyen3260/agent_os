import {DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  effect,
  HostListener,
  inject,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {combineLatest} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {Command, InvitationService} from './invitation.service';
import {InvitationScopeComponent} from './invitation-scope.component';
import {
  Correlation,
  email,
  InvitationContext,
  InvitationPage,
  normalizeEmail,
  Operation,
  Receipt,
  Status,
  statuses,
  uuid,
} from './invitation-validation';

class InvitationAuthorityDenied extends Error {}

@Component({
  selector: 'app-invitation-admin',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, InvitationScopeComponent],
  templateUrl: './invitation-admin.component.html',
  styleUrl: './invitations.css',
})
export class InvitationAdminComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(InvitationService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  readonly workspace = signal<Workspace | null>(null);
  readonly list = signal<InvitationPage | null>(null);
  readonly detail = signal<InvitationContext | null>(null);
  readonly receipt = signal<Receipt | null>(null);
  readonly phase = signal<
    'loading' | 'edit' | 'review' | 'result' | 'unknown' | 'denied'
  >('loading');
  readonly message = signal('');
  readonly statuses = statuses;
  org = '';
  id = '';
  page = 0;
  status: Status = 'ALL';
  creating = false;
  recipient = '';
  acknowledged = false;
  busy = false;
  operation: Operation = 'CREATE_INVITATION';
  private epoch = 0;
  private actor = this.auth.user()?.id;
  private command: Command | null = null;
  private recovery: Readonly<Correlation> | null = null;
  private dispatched = false;
  private destroyed = false;
  constructor() {
    effect(() => {
      const actor = this.auth.user()?.id;
      if (actor !== this.actor) {
        this.actor = actor;
        this.clear(false);
        this.phase.set('denied');
      }
    });
    combineLatest([this.route.paramMap, this.route.queryParamMap])
      .pipe(takeUntilDestroyed())
      .subscribe(([p, q]) => {
        this.clear(false);
        this.org = p.get('id') || '';
        this.id = p.get('invitationId') || '';
        this.creating =
          this.route.snapshot.routeConfig?.path?.endsWith('/new') === true;
        const raw = q.get('page') || '0';
        this.page = /^(0|[1-9]\d*)$/.test(raw) ? Number(raw) : -1;
        this.status = (q.get('status') || 'ALL') as Status;
        if (
          q.keys.some(k => !['page', 'status'].includes(k)) ||
          q.keys.some(k => q.getAll(k).length !== 1)
        ) {
          this.phase.set('denied');
          return;
        }
        void this.load();
      });
    this.destroy.onDestroy(() => {
      this.destroyed = true;
      this.clear(false);
    });
  }
  private clear(retain: boolean): void {
    this.epoch++;
    this.workspace.set(null);
    this.list.set(null);
    this.detail.set(null);
    this.receipt.set(null);
    this.recipient = '';
    this.acknowledged = false;
    this.command = null;
    this.message.set('');
    this.busy = false;
    if (!retain) {
      this.recovery = null;
      this.dispatched = false;
    }
  }
  private active(epoch: number, actor: string | undefined): boolean {
    return (
      !this.destroyed && epoch === this.epoch && actor === this.auth.user()?.id
    );
  }
  private async gate(): Promise<Workspace> {
    const expected = this.actor;
    const actor = await this.auth.session();
    if (!expected || actor.id !== expected)
      throw new InvitationAuthorityDenied();
    const workspace = await this.auth.getWorkspace(this.org);
    if (
      workspace.id !== this.org ||
      workspace.role !== 'ADMIN' ||
      this.auth.user()?.id !== expected
    )
      throw new InvitationAuthorityDenied();
    return workspace;
  }
  async load(): Promise<void> {
    this.clear(!!this.recovery);
    this.phase.set('loading');
    if (
      !uuid(this.org) ||
      (this.id && !uuid(this.id)) ||
      this.page < 0 ||
      this.page > 100000 ||
      !statuses.includes(this.status)
    ) {
      this.phase.set('denied');
      return;
    }
    const epoch = this.epoch,
      actor = this.actor;
    try {
      const workspace = await this.gate();
      if (!this.active(epoch, actor)) return;
      if (this.recovery) {
        this.workspace.set(workspace);
        this.phase.set('unknown');
        return;
      }
      const result = this.creating
        ? null
        : this.id
          ? await this.api.context(this.org, this.id)
          : await this.api.list(this.org, this.page, this.status);
      if (!this.active(epoch, actor)) return;
      this.workspace.set(workspace);
      if (result && 'invitation' in result) this.detail.set(result);
      else if (result) this.list.set(result);
      this.phase.set('edit');
    } catch {
      if (this.active(epoch, actor)) {
        this.clear(!!this.recovery);
        this.phase.set('denied');
      }
    }
  }
  async review(operation: Operation): Promise<void> {
    if (this.busy || this.phase() !== 'edit') return;
    const recipient = normalizeEmail(this.recipient);
    if (operation === 'CREATE_INVITATION' && !email(recipient)) {
      this.message.set('Email người nhận không hợp lệ.');
      return;
    }
    this.busy = true;
    this.phase.set('loading');
    const epoch = this.epoch,
      actor = this.actor;
    try {
      await this.gate();
      if (!this.active(epoch, actor)) return;
      const detail =
        operation === 'CREATE_INVITATION'
          ? null
          : await this.api.context(this.org, this.id);
      if (!this.active(epoch, actor)) return;
      if (
        detail &&
        !(operation === 'RESEND_INVITATION'
          ? detail.canResend
          : detail.canRevoke)
      )
        throw new Error('unavailable');
      this.detail.set(detail);
      this.recipient = detail?.invitation.email || recipient;
      this.operation = operation;
      const correlation: Correlation = {
        organizationId: this.org,
        actorId: actor!,
        key: crypto.randomUUID(),
        operation,
        email: this.recipient,
        ...(detail
          ? {invitationId: this.id, revision: detail.invitation.revision}
          : {}),
      };
      this.command = Object.freeze({
        correlation: Object.freeze(correlation),
        body: Object.freeze(
          detail
            ? {
                expectedRevision: detail.invitation.revision,
                acknowledgement: true as const,
              }
            : {email: recipient, acknowledgement: true as const},
        ),
      });
      this.acknowledged = false;
      this.phase.set('review');
    } catch {
      if (this.active(epoch, actor)) {
        this.clear(false);
        this.phase.set('denied');
      }
    } finally {
      if (this.active(epoch, actor)) this.busy = false;
    }
  }
  back(): void {
    if (!this.busy && !this.dispatched) {
      this.command = null;
      this.acknowledged = false;
      if (!this.creating) this.recipient = '';
      this.phase.set('edit');
    }
  }
  async submit(): Promise<void> {
    if (
      this.busy ||
      !this.command ||
      (!this.acknowledged && this.phase() !== 'unknown')
    )
      return;
    const command = this.command;
    const epoch = this.epoch,
      actor = this.actor;
    this.busy = true;
    this.phase.set('loading');
    try {
      await this.gate();
      if (!this.active(epoch, actor)) return;
      const receipt = await this.api.dispatch(command, () => {
        if (!this.active(epoch, actor)) throw new Error('stale');
        this.dispatched = true;
        this.recovery = Object.freeze({
          organizationId: command.correlation.organizationId,
          actorId: command.correlation.actorId,
          key: command.correlation.key,
          operation: command.correlation.operation,
          ...(command.correlation.invitationId
            ? {invitationId: command.correlation.invitationId}
            : {}),
        });
      });
      if (!this.active(epoch, actor)) return;
      this.receipt.set(receipt);
      this.command = null;
      this.recovery = null;
      this.dispatched = false;
      this.recipient = '';
      this.phase.set('result');
    } catch (error) {
      if (this.active(epoch, actor)) {
        if (
          error instanceof InvitationAuthorityDenied ||
          error instanceof AuthFailure ||
          (error instanceof HttpErrorResponse &&
            [401, 403, 404].includes(error.status))
        ) {
          this.clear(!!this.recovery);
          this.phase.set('denied');
        } else if (this.dispatched) this.phase.set('unknown');
        else {
          this.clear(false);
          this.phase.set('denied');
        }
      }
    } finally {
      if (this.active(epoch, actor)) this.busy = false;
    }
  }
  async recover(): Promise<void> {
    if (this.busy || !this.recovery) return;
    const c = this.recovery,
      epoch = this.epoch,
      actor = this.actor;
    this.busy = true;
    this.phase.set('loading');
    try {
      const workspace = await this.gate();
      if (!this.active(epoch, actor)) return;
      this.workspace.set(workspace);
      const receipt = await this.api.receipt(c);
      if (!this.active(epoch, actor)) return;
      if (receipt) {
        this.receipt.set(receipt);
        this.command = null;
        this.recovery = null;
        this.dispatched = false;
        this.phase.set('result');
      } else {
        this.phase.set('unknown');
        this.message.set('Chưa có biên nhận. Kết quả vẫn chưa xác định.');
      }
    } catch (error) {
      if (this.active(epoch, actor)) {
        if (
          error instanceof InvitationAuthorityDenied ||
          error instanceof AuthFailure ||
          (error instanceof HttpErrorResponse &&
            [401, 403, 404].includes(error.status))
        ) {
          this.clear(true);
          this.phase.set('denied');
        } else {
          this.phase.set('unknown');
          this.message.set(
            'Chưa thể kiểm tra biên nhận. Kết quả vẫn chưa xác định.',
          );
        }
      }
    } finally {
      if (this.active(epoch, actor)) this.busy = false;
    }
  }
  statusLabel(value: string): string {
    const labels: Record<string, string> = {
      ALL: 'Tất cả',
      PENDING: 'Đang chờ chấp nhận',
      ACCEPTED: 'Đã chấp nhận',
      REVOKED: 'Đã thu hồi',
      EXPIRED: 'Đã hết hạn',
      QUEUED: 'Đang chờ gửi',
      SENT: 'Đã gửi qua máy chủ thư',
      FAILED: 'Gửi email chưa thành công',
      CANCELLED: 'Đã hủy gửi',
    };
    return labels[value] || value;
  }
  get finalLabel(): string {
    return this.operation === 'CREATE_INVITATION'
      ? 'Gửi lời mời'
      : this.operation === 'RESEND_INVITATION'
        ? 'Gửi lại lời mời'
        : 'Thu hồi lời mời';
  }
  refreshDetail(id: string): void {
    if (id === this.id && !this.creating) void this.load();
  }
  filter(status: Status): void {
    if (statuses.includes(status))
      void this.router.navigate(['/workspace', this.org, 'invitations'], {
        queryParams: {status},
      });
  }
  paginate(page: number): void {
    void this.router.navigate(['/workspace', this.org, 'invitations'], {
      queryParams: {page, status: this.status},
    });
  }
  get validEmail(): boolean {
    return email(normalizeEmail(this.recipient));
  }
  get canRecover(): boolean {
    return !!this.recovery;
  }
  get canRetry(): boolean {
    return !!this.command;
  }
  @HostListener('window:focus')
  onFocus(): void {
    void this.revalidate();
  }
  @HostListener('document:visibilitychange')
  onVisibility(): void {
    if (document.visibilityState === 'visible') void this.revalidate();
  }
  private async revalidate(): Promise<void> {
    // Remove private DOM before any asynchronous authority check.
    if (this.destroyed || (this.phase() === 'loading' && !this.busy)) return;
    if (this.busy) {
      this.clear(!!this.recovery);
      this.phase.set('loading');
      const epoch = this.epoch,
        actor = this.actor;
      try {
        const workspace = await this.gate();
        if (!this.active(epoch, actor)) return;
        this.workspace.set(workspace);
        this.phase.set(this.recovery ? 'unknown' : 'edit');
      } catch {
        if (this.active(epoch, actor)) {
          this.clear(!!this.recovery);
          this.phase.set('denied');
        }
      }
      return;
    }
    const command = this.command,
      recipient = this.recipient,
      acknowledged = this.acknowledged,
      phase = this.phase();
    const detail = this.detail(),
      list = this.list(),
      receipt = this.receipt();
    this.clear(!!this.recovery);
    this.phase.set('loading');
    const epoch = this.epoch,
      actor = this.actor;
    try {
      const workspace = await this.gate();
      if (!this.active(epoch, actor)) return;
      this.workspace.set(workspace);
      this.command = command;
      this.recipient = recipient;
      this.acknowledged = acknowledged;
      this.detail.set(detail);
      this.list.set(list);
      this.receipt.set(receipt);
      this.phase.set(phase === 'loading' ? 'edit' : phase);
    } catch {
      if (this.active(epoch, actor)) {
        this.clear(!!this.recovery);
        this.phase.set('denied');
      }
    }
  }
  canLeave(): boolean {
    return (
      !(this.recipient || this.command || this.recovery || this.busy) ||
      window.confirm('Rời trang và bỏ thông tin lời mời chưa hoàn tất?')
    );
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.recipient || this.command || this.recovery || this.busy) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
}

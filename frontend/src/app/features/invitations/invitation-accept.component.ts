import {
  Component,
  DestroyRef,
  ElementRef,
  ViewChild,
  effect,
  HostListener,
  inject,
  signal,
} from '@angular/core';
import {DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {RouterLink} from '@angular/router';
import {AuthFailure, AuthService} from '../../core/auth/auth.service';
import {InvitationService} from './invitation.service';
import {InvitationScopeComponent} from './invitation-scope.component';
import {
  Acceptance,
  displayName,
  Inspection,
  normalizeEmail,
  password,
  strip,
} from './invitation-validation';
@Component({
  selector: 'app-invitation-accept',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, InvitationScopeComponent],
  templateUrl: './invitation-accept.component.html',
  styleUrl: './invitations.css',
})
export class InvitationAcceptComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(InvitationService);
  private readonly destroy = inject(DestroyRef);
  readonly context = signal<Inspection | null>(null);
  readonly result = signal<Acceptance | null>(null);
  readonly phase = signal<
    'loading' | 'review' | 'invalid' | 'unavailable' | 'success' | 'unknown'
  >('loading');
  readonly message = signal('');
  name = '';
  newPassword = '';
  confirmation = '';
  loginEmail = '';
  loginPassword = '';
  acknowledged = false;
  busy = false;
  @ViewChild('nameInput') private nameInput?: ElementRef<HTMLInputElement>;
  @ViewChild('passwordInput')
  private passwordInput?: ElementRef<HTMLInputElement>;
  @ViewChild('confirmationInput')
  private confirmationInput?: ElementRef<HTMLInputElement>;
  nameError = '';
  passwordError = '';
  confirmationError = '';
  private token = '';
  private epoch = 0;
  private flow = 0;
  private actor = this.auth.user()?.id;
  private authenticating = false;
  private dispatched = false;
  constructor() {
    this.token = this.consumeFragment();
    effect(() => {
      const actor = this.auth.user()?.id;
      if (actor !== this.actor) {
        this.actor = actor;
        const completed = this.phase() === 'success';
        this.clean();
        this.result.set(null);
        if (completed) {
          this.token = '';
          this.phase.set('invalid');
        } else if (this.dispatched) {
          this.token = '';
          this.phase.set('unknown');
          this.busy = false;
        }
        if (
          !this.authenticating &&
          !this.dispatched &&
          this.phase() !== 'success' &&
          this.phase() !== 'unknown'
        )
          void this.inspect();
      }
    });
    this.destroy.onDestroy(() => {
      this.epoch++;
      this.flow++;
      this.token = '';
      this.clean();
    });
    void this.inspect();
  }
  private consumeFragment(): string {
    const fragment = window.location.hash;
    window.history.replaceState(
      window.history.state,
      '',
      window.location.pathname,
    );
    return /^#token=([A-Za-z0-9_-]{43})$/.exec(fragment)?.[1] || '';
  }
  @HostListener('window:hashchange')
  replaceInvitation(): void {
    // A same-document link does not recreate this component. Scrub it even if
    // the user chooses to keep the current draft or unresolved operation.
    const token = this.consumeFragment();
    if (!this.canLeave()) return;
    this.flow++;
    this.clean();
    this.result.set(null);
    this.token = token;
    this.dispatched = false;
    this.authenticating = false;
    this.busy = false;
    this.phase.set('loading');
    void this.inspect();
  }
  private clean(): void {
    this.epoch++;
    this.nameError = '';
    this.passwordError = '';
    this.confirmationError = '';
    this.name = '';
    this.newPassword = '';
    this.confirmation = '';
    this.loginEmail = '';
    this.loginPassword = '';
    this.acknowledged = false;
    this.context.set(null);
    this.message.set('');
  }
  private active(epoch: number): boolean {
    return epoch === this.epoch && !this.destroy.destroyed;
  }
  private inspectionFailure(error: unknown): 'invalid' | 'unavailable' {
    return error instanceof HttpErrorResponse &&
      error.status === 400 &&
      error.error?.error?.code === 'INVALID_INVITATION'
      ? 'invalid'
      : 'unavailable';
  }
  async inspect(): Promise<void> {
    if (
      this.dispatched ||
      this.phase() === 'unknown' ||
      this.phase() === 'success'
    )
      return;
    this.clean();
    this.phase.set('loading');
    const epoch = this.epoch;
    if (!this.token) {
      this.phase.set('invalid');
      return;
    }
    this.busy = true;
    try {
      try {
        await this.auth.session();
      } catch (error) {
        if (!(error instanceof AuthFailure && error.kind === 'unauthorized'))
          throw error;
      }
      // Session synchronization can invalidate the earlier epoch through the auth effect.
      if (!this.active(epoch)) return;
      const actor = this.auth.user()?.id;
      const context = await this.api.inspect(this.token);
      if (!this.active(epoch) || actor !== this.auth.user()?.id) return;
      this.context.set(context);
      this.phase.set('review');
    } catch (error) {
      if (this.active(epoch)) {
        this.context.set(null);
        this.phase.set(this.inspectionFailure(error));
      }
    } finally {
      if (this.active(epoch)) this.busy = false;
    }
  }
  async login(): Promise<void> {
    if (this.busy || this.context()?.state !== 'SIGN_IN') return;
    const email = normalizeEmail(this.loginEmail),
      secret = this.loginPassword;
    const flow = this.flow;
    this.clean();
    this.authenticating = true;
    this.busy = true;
    this.phase.set('loading');
    let failed = false;
    try {
      await this.auth.login(email, secret);
    } catch {
      failed = true;
    } finally {
      if (flow === this.flow && !this.destroy.destroyed) {
        this.authenticating = false;
        this.busy = false;
        await this.inspect();
        if (failed && flow === this.flow && !this.destroy.destroyed)
          this.message.set(
            'Không thể đăng nhập. Kiểm tra thông tin và thử lại.',
          );
      }
    }
  }
  async logout(): Promise<void> {
    if (
      this.busy ||
      !['MISMATCH', 'READY'].includes(this.context()?.state || '')
    )
      return;
    this.clean();
    const flow = this.flow;
    this.authenticating = true;
    this.busy = true;
    this.phase.set('loading');
    try {
      await this.auth.logout();
    } catch {
      this.message.set('Không thể đăng xuất. Vui lòng thử lại.');
    } finally {
      if (flow === this.flow && !this.destroy.destroyed) {
        this.authenticating = false;
        this.busy = false;
        await this.inspect();
      }
    }
  }
  async accept(): Promise<void> {
    const context = this.context();
    if (
      this.busy ||
      this.dispatched ||
      !this.acknowledged ||
      !context ||
      !['NEW_ACCOUNT', 'READY'].includes(context.state)
    )
      return;
    const fresh = context.state === 'NEW_ACCOUNT';
    const name = strip(this.name),
      secret = this.newPassword;
    this.nameError =
      fresh && !displayName(name)
        ? 'Nhập họ và tên từ 1 đến 255 ký tự hợp lệ.'
        : '';
    this.passwordError =
      fresh && !password(secret)
        ? 'Dùng ít nhất 14 ký tự; mật khẩu này chưa đáp ứng giới hạn cho phép.'
        : '';
    this.confirmationError =
      fresh && secret !== this.confirmation
        ? 'Mật khẩu nhập lại chưa khớp.'
        : '';
    if (this.nameError || this.passwordError || this.confirmationError) {
      (this.nameError
        ? this.nameInput
        : this.passwordError
          ? this.passwordInput
          : this.confirmationInput
      )?.nativeElement.focus();
      return;
    }
    const token = this.token,
      epoch = this.epoch,
      actor = this.auth.user()?.id;
    this.busy = true;
    this.newPassword = '';
    this.confirmation = '';
    this.name = '';
    this.message.set('');
    this.phase.set('loading');
    try {
      try {
        await this.auth.session();
      } catch (error) {
        if (
          !(
            fresh &&
            error instanceof AuthFailure &&
            error.kind === 'unauthorized'
          )
        )
          throw error;
      }
      if (!this.active(epoch) || actor !== this.auth.user()?.id) return;
      const current = await this.api.inspect(token);
      if (!this.active(epoch) || actor !== this.auth.user()?.id) return;
      if (
        current.state !== context.state ||
        current.organizationId !== context.organizationId
      ) {
        await this.inspect();
        return;
      }
      const result = await this.api.accept(
        token,
        fresh ? name : null,
        fresh ? secret : null,
        context.organizationId,
        () => {
          if (!this.active(epoch) || actor !== this.auth.user()?.id)
            throw new Error('stale');
          this.dispatched = true;
        },
      );
      if (!this.active(epoch) || actor !== this.auth.user()?.id) return;
      this.clean();
      this.busy = false;
      this.token = '';
      this.result.set(result);
      this.phase.set('success');
    } catch (error) {
      if (this.active(epoch)) {
        this.clean();
        this.busy = false;
        if (this.dispatched) {
          this.token = '';
          this.phase.set('unknown');
        } else this.phase.set(this.inspectionFailure(error));
      }
    } finally {
      if (this.active(epoch)) this.busy = false;
    }
  }
  @HostListener('window:focus')
  focus(): void {
    if (this.phase() === 'unknown' || this.phase() === 'success') {
      void this.auth.session().catch(() => undefined);
      return;
    }
    if (!this.busy && !this.dispatched) {
      void this.inspect();
      return;
    }
    // A focus/session check never re-inspects a possibly consumed capability.
    this.clean();
    if (this.dispatched) {
      this.token = '';
      this.phase.set('unknown');
    } else this.phase.set('loading');
    const epoch = this.epoch;
    void this.auth
      .session()
      .catch(() => undefined)
      .finally(() => {
        if (this.active(epoch) && !this.dispatched && !this.authenticating)
          void this.inspect();
      });
  }
  @HostListener('document:visibilitychange')
  visibility(): void {
    if (document.visibilityState === 'visible') this.focus();
  }
  canLeave(): boolean {
    return (
      !this.hasUnfinishedWork() ||
      window.confirm(
        this.phase() === 'unknown' || this.dispatched
          ? 'Kết quả tham gia có thể đã được ghi nhận. Rời trang và kiểm tra lại sau khi đăng nhập?'
          : 'Rời trang và bỏ thông tin chưa hoàn tất?',
      )
    );
  }
  private hasUnfinishedWork(): boolean {
    return (
      this.phase() !== 'success' &&
      !!(
        this.name ||
        this.newPassword ||
        this.loginPassword ||
        this.acknowledged ||
        this.busy ||
        this.phase() === 'unknown'
      )
    );
  }
  @HostListener('window:beforeunload', ['$event'])
  unload(event: BeforeUnloadEvent): void {
    if (this.hasUnfinishedWork()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
}

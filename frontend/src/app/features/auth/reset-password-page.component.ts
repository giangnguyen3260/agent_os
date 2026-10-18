import {DOCUMENT} from '@angular/common';
import {Component, inject, OnDestroy, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {RouterLink} from '@angular/router';
import {AuthFailure, AuthService} from '../../core/auth/auth.service';

@Component({
  selector: 'app-reset-password-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <main class="auth-wrapper auth-recovery">
      <header class="auth-brand">
        <div>agentos</div>
        <p>Không gian làm việc cho đội ngũ AI</p>
      </header>
      @if (state() === 'expired') {
        <section
          class="auth-card"
          aria-labelledby="expired-heading"
          aria-live="polite"
        >
          <header class="auth-login-heading">
            <h1 id="expired-heading">Liên kết không còn hiệu lực</h1>
            <p class="auth-subtitle">
              Liên kết đặt lại mật khẩu đã hết hạn hoặc đã được sử dụng.
            </p>
          </header>
          <a class="button auth-action" routerLink="/forgot-password"
            >Yêu cầu liên kết mới</a
          >
          <a class="button secondary auth-action" routerLink="/login"
            >Về đăng nhập</a
          >
        </section>
      } @else if (state() === 'success') {
        <section
          class="auth-card"
          aria-labelledby="success-heading"
          aria-live="polite"
        >
          <header class="auth-login-heading">
            <h1 id="success-heading">Đã đổi mật khẩu</h1>
            <p class="auth-subtitle">Bạn có thể đăng nhập bằng mật khẩu mới.</p>
          </header>
          <a class="button auth-action" routerLink="/login">Về đăng nhập</a>
        </section>
      } @else {
        <form
          class="auth-card"
          (ngSubmit)="submit()"
          novalidate
          [attr.aria-busy]="pending()"
        >
          <header class="auth-login-heading">
            <h1>Đặt lại mật khẩu</h1>
            <p class="auth-subtitle">Tạo mật khẩu mới cho tài khoản của bạn.</p>
          </header>
          <p id="password-policy" class="auth-recovery-policy">
            Mật khẩu phải có ít nhất 14 ký tự và không vượt quá 72 byte UTF-8.
          </p>
          @if (error()) {
            <p class="auth-error" role="alert">{{ error() }}</p>
          }
          <div class="auth-field">
            <label for="new-password">Mật khẩu mới</label>
            <input
              id="new-password"
              name="password"
              type="password"
              autocomplete="new-password"
              required
              [(ngModel)]="password"
              [readOnly]="pending()"
              [attr.aria-invalid]="passwordError() ? 'true' : null"
              [attr.aria-describedby]="
                passwordError()
                  ? 'password-policy new-password-error'
                  : 'password-policy'
              "
            />
            @if (passwordError()) {
              <p id="new-password-error" class="field-error" role="alert">
                {{ passwordError() }}
              </p>
            }
          </div>
          <div class="auth-field">
            <label for="confirm-password">Nhập lại mật khẩu</label>
            <input
              id="confirm-password"
              name="confirmation"
              type="password"
              autocomplete="new-password"
              required
              [(ngModel)]="confirmation"
              [readOnly]="pending()"
              [attr.aria-invalid]="confirmationError() ? 'true' : null"
              [attr.aria-describedby]="
                confirmationError() ? 'confirmation-error' : null
              "
            />
            @if (confirmationError()) {
              <p id="confirmation-error" class="field-error" role="alert">
                {{ confirmationError() }}
              </p>
            }
          </div>
          <button
            class="button auth-action"
            type="submit"
            [disabled]="pending()"
          >
            Lưu mật khẩu mới
          </button>
        </form>
      }
    </main>
  `,
})
export class ResetPasswordPageComponent implements OnDestroy {
  private readonly auth = inject(AuthService);
  private token = '';
  private destroyed = false;
  password = '';
  confirmation = '';
  readonly state = signal<'form' | 'expired' | 'success'>('expired');
  readonly pending = signal(false);
  readonly error = signal('');
  readonly passwordError = signal('');
  readonly confirmationError = signal('');

  constructor() {
    const window = inject(DOCUMENT).defaultView;
    if (!window) return;
    const fragment = window.location.hash;
    // Consume once and erase immediately. Only component memory retains the token.
    // Reload after erasure intentionally expires the flow; reopen the email link.
    // Do not subscribe to route fragments: erasure must not discard this token.
    window.history.replaceState(
      window.history.state,
      '',
      window.location.pathname + window.location.search,
    );
    const match = /^#token=([A-Za-z0-9_-]{43})$/.exec(fragment);
    if (match) {
      this.token = match[1];
      this.state.set('form');
    }
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.clearSecrets();
  }

  private clearSecrets(): void {
    this.token = '';
    this.password = '';
    this.confirmation = '';
  }

  async submit(): Promise<void> {
    if (this.pending() || this.state() !== 'form' || !this.token) return;
    this.error.set('');
    this.passwordError.set(
      [...this.password].length < 14
        ? 'Mật khẩu phải có ít nhất 14 ký tự.'
        : new TextEncoder().encode(this.password).length > 72
          ? 'Mật khẩu không được vượt quá 72 byte UTF-8.'
          : '',
    );
    this.confirmationError.set(
      !this.confirmation
        ? 'Vui lòng nhập lại mật khẩu.'
        : this.confirmation !== this.password
          ? 'Mật khẩu nhập lại không khớp.'
          : '',
    );
    if (this.passwordError() || this.confirmationError()) return;
    this.pending.set(true);
    try {
      await this.auth.confirmRecovery(this.token, this.password);
      this.clearSecrets();
      if (!this.destroyed) this.state.set('success');
    } catch (error) {
      if (this.destroyed) return;
      if (
        error instanceof AuthFailure &&
        error.code === 'INVALID_RESET_TOKEN'
      ) {
        this.clearSecrets();
        this.state.set('expired');
      } else {
        this.error.set(
          error instanceof AuthFailure && error.kind === 'network'
            ? 'Không thể kết nối. Kiểm tra mạng và thử lại.'
            : 'Không thể đổi mật khẩu lúc này. Vui lòng thử lại.',
        );
      }
    } finally {
      this.pending.set(false);
    }
  }
}

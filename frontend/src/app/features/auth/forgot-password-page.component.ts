import {Component, inject, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {RouterLink} from '@angular/router';
import {AuthFailure, AuthService} from '../../core/auth/auth.service';

@Component({
  selector: 'app-forgot-password-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <main class="auth-wrapper auth-recovery">
      <header class="auth-brand">
        <div>agentos</div>
        <p>Không gian làm việc cho đội ngũ AI</p>
      </header>
      @if (sent()) {
        <section
          class="auth-card"
          aria-labelledby="sent-heading"
          aria-live="polite"
        >
          <header class="auth-login-heading">
            <h1 id="sent-heading">Kiểm tra email</h1>
            <p class="auth-subtitle">
              Nếu email này có tài khoản, bạn sẽ nhận được hướng dẫn đặt lại mật
              khẩu.
            </p>
          </header>
          <section class="auth-recovery-notice" aria-labelledby="next-heading">
            <h2 id="next-heading">Bước tiếp theo</h2>
            <p>Kiểm tra hộp thư đến và thư rác.</p>
            <p>Chỉ dùng liên kết mới nhất bạn nhận được.</p>
          </section>
          <a class="button auth-action" routerLink="/login">Về đăng nhập</a>
          <p class="auth-small">
            Không tiết lộ email có tồn tại trong hệ thống hay không.
          </p>
        </section>
      } @else {
        <form
          class="auth-card"
          (ngSubmit)="submit()"
          novalidate
          [attr.aria-busy]="pending()"
        >
          <header class="auth-login-heading">
            <h1>Quên mật khẩu</h1>
            <p class="auth-subtitle">Nhập email bạn dùng để đăng nhập.</p>
          </header>
          @if (error()) {
            <p class="auth-error" role="alert">{{ error() }}</p>
          }
          <div class="auth-field">
            <label for="recovery-email">Email</label>
            <input
              id="recovery-email"
              name="email"
              type="email"
              autocomplete="email"
              required
              maxlength="254"
              [(ngModel)]="email"
              [readOnly]="pending()"
              [attr.aria-invalid]="emailError() ? 'true' : null"
              [attr.aria-describedby]="
                emailError() ? 'recovery-email-error' : null
              "
            />
            @if (emailError()) {
              <p id="recovery-email-error" class="field-error" role="alert">
                {{ emailError() }}
              </p>
            }
          </div>
          <button
            class="button auth-action"
            type="submit"
            [disabled]="pending()"
          >
            Gửi liên kết đặt lại
          </button>
          <a class="button secondary auth-action" routerLink="/login"
            >Về đăng nhập</a
          >
        </form>
      }
    </main>
  `,
})
export class ForgotPasswordPageComponent {
  private readonly auth = inject(AuthService);
  email = '';
  readonly pending = signal(false);
  readonly sent = signal(false);
  readonly error = signal('');
  readonly emailError = signal('');

  async submit(): Promise<void> {
    if (this.pending() || this.sent()) return;
    this.error.set('');
    const email = this.email.trim();
    this.emailError.set(
      !email
        ? 'Vui lòng nhập email.'
        : email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
          ? 'Vui lòng nhập email hợp lệ (tối đa 254 ký tự).'
          : '',
    );
    if (this.emailError()) return;
    this.pending.set(true);
    try {
      await this.auth.requestRecovery(email);
      this.email = '';
      this.sent.set(true);
    } catch (error) {
      this.error.set(
        error instanceof AuthFailure && error.kind === 'network'
          ? 'Không thể kết nối. Kiểm tra mạng và thử lại.'
          : 'Không thể gửi yêu cầu lúc này. Vui lòng thử lại.',
      );
    } finally {
      this.pending.set(false);
    }
  }
}

import {Component, inject, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {AuthFailure, AuthService} from '../../core/auth/auth.service';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  template: `
    <main class="auth-wrapper">
      <header class="auth-brand">
        <div>agentos</div>
        <p>Không gian làm việc cho đội ngũ AI</p>
      </header>
      <form
        class="auth-card"
        (ngSubmit)="submit()"
        novalidate
        [attr.aria-busy]="pending()"
      >
        <header class="auth-login-heading">
          <h1>Đăng nhập</h1>
          <p class="auth-subtitle">
            Dùng tài khoản cá nhân để vào workspace của bạn.
          </p>
        </header>
        @if (error()) {
          <p class="auth-error" role="alert">{{ error() }}</p>
        }
        <div class="auth-field">
          <label for="email">Email</label>
          <input
            id="email"
            name="email"
            type="email"
            autocomplete="username"
            required
            maxlength="254"
            [(ngModel)]="email"
            [readOnly]="pending()"
            [attr.aria-invalid]="emailError() ? 'true' : null"
            [attr.aria-describedby]="emailError() ? 'email-error' : null"
            placeholder="Nhập email của bạn"
          />
          @if (emailError()) {
            <p id="email-error" class="field-error">{{ emailError() }}</p>
          }
        </div>
        <div class="auth-field">
          <label for="password">Mật khẩu</label>
          <input
            id="password"
            name="password"
            type="password"
            autocomplete="current-password"
            required
            maxlength="72"
            [(ngModel)]="password"
            [readOnly]="pending()"
            [attr.aria-invalid]="passwordError() ? 'true' : null"
            [attr.aria-describedby]="passwordError() ? 'password-error' : null"
            placeholder="Nhập mật khẩu"
          />
          @if (passwordError()) {
            <p id="password-error" class="field-error">{{ passwordError() }}</p>
          }
        </div>
        <button class="button auth-action" type="submit" [disabled]="pending()">
          {{ pending() ? 'Đang đăng nhập…' : 'Đăng nhập' }}
        </button>
        <a class="button secondary auth-action" routerLink="/forgot-password">
          Quên mật khẩu
        </a>
        @if (!error()) {
          <p class="auth-small">
            Liên hệ quản trị viên nếu bạn chưa được cấp quyền truy cập.
          </p>
        }
      </form>
    </main>
  `,
})
export class LoginPageComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  email = '';
  password = '';
  readonly pending = signal(false);
  readonly emailError = signal('');
  readonly passwordError = signal('');
  readonly error = signal(
    this.route.snapshot.queryParamMap.get('sessionError') === 'unavailable'
      ? 'Không thể kiểm tra phiên đăng nhập. Vui lòng thử lại.'
      : '',
  );

  async submit(): Promise<void> {
    if (this.pending()) return;
    this.error.set('');
    const email = this.email.trim();
    this.emailError.set(
      !email
        ? 'Vui lòng nhập email.'
        : email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
          ? 'Vui lòng nhập email hợp lệ (tối đa 254 ký tự).'
          : '',
    );
    this.passwordError.set(
      !this.password
        ? 'Vui lòng nhập mật khẩu.'
        : new TextEncoder().encode(this.password).length > 72
          ? 'Mật khẩu không được vượt quá 72 byte.'
          : '',
    );
    if (this.emailError() || this.passwordError()) return;
    this.pending.set(true);
    try {
      await this.auth.login(email, this.password);
      this.password = '';
      await this.router.navigateByUrl('/workspace');
    } catch (error) {
      this.password = '';
      this.error.set(
        error instanceof AuthFailure && error.kind === 'unauthorized'
          ? 'Email hoặc mật khẩu không đúng. Vui lòng thử lại.'
          : error instanceof AuthFailure && error.kind === 'network'
            ? 'Không thể kết nối. Kiểm tra mạng và thử đăng nhập lại.'
            : 'Không thể đăng nhập lúc này. Vui lòng thử lại.',
      );
    } finally {
      this.pending.set(false);
    }
  }
}

import {Component, inject, OnInit, signal} from '@angular/core';
import {Router} from '@angular/router';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';

@Component({
  selector: 'app-workspace-page',
  standalone: true,
  template: `
    <main class="auth-wrapper">
      <header class="auth-brand">
        <div>AgentOS</div>
        <p>Không gian làm việc</p>
      </header>
      <section
        class="auth-card"
        aria-labelledby="workspace-title"
        [attr.aria-busy]="loading()"
      >
        <header>
          <h1 id="workspace-title">Không gian làm việc</h1>
          <p class="auth-subtitle">
            {{ auth.user()?.displayName }} · {{ auth.user()?.email }}
          </p>
        </header>
        @if (loading()) {
          <p role="status">Đang tải không gian làm việc…</p>
        } @else if (loadError()) {
          <p class="auth-error" role="alert">{{ loadError() }}</p>
          <button
            class="button secondary auth-action"
            (click)="load()"
            type="button"
          >
            Thử lại
          </button>
        } @else {
          @if (workspaces().length) {
            <ul class="workspace-list">
              @for (workspace of workspaces(); track workspace.id) {
                <li>{{ workspace.name }}</li>
              }
            </ul>
            <p class="auth-small">
              Đây là các không gian làm việc bạn đang tham gia. Chức năng mở
              không gian làm việc chưa khả dụng.
            </p>
          } @else {
            <p role="status">
              Bạn chưa tham gia không gian làm việc nào. Liên hệ quản trị viên
              để được cấp quyền truy cập.
            </p>
          }
        }
        @if (logoutError()) {
          <p class="auth-error" role="alert">{{ logoutError() }}</p>
        }
        <button
          class="button secondary auth-action"
          type="button"
          [disabled]="loggingOut()"
          (click)="logout()"
        >
          {{ loggingOut() ? 'Đang đăng xuất…' : 'Đăng xuất' }}
        </button>
      </section>
    </main>
  `,
})
export class WorkspacePageComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly workspaces = signal<Workspace[]>([]);
  readonly loading = signal(false);
  readonly loggingOut = signal(false);
  readonly loadError = signal('');
  readonly logoutError = signal('');
  ngOnInit(): void {
    void this.load();
  }
  async load(): Promise<void> {
    if (this.loading()) return;
    this.loading.set(true);
    this.loadError.set('');
    try {
      this.workspaces.set(await this.auth.workspaces());
    } catch (error) {
      if (error instanceof AuthFailure && error.kind === 'unauthorized')
        await this.router.navigateByUrl('/login');
      else
        this.loadError.set(
          error instanceof AuthFailure && error.kind === 'network'
            ? 'Không thể kết nối. Kiểm tra mạng và thử lại.'
            : 'Không thể tải không gian làm việc. Vui lòng thử lại.',
        );
    } finally {
      this.loading.set(false);
    }
  }
  async logout(): Promise<void> {
    if (this.loggingOut()) return;
    this.loggingOut.set(true);
    this.logoutError.set('');
    try {
      await this.auth.logout();
      await this.router.navigateByUrl('/login');
    } catch (error) {
      if (error instanceof AuthFailure && error.kind === 'unauthorized')
        await this.router.navigateByUrl('/login');
      else this.logoutError.set('Không thể đăng xuất. Vui lòng thử lại.');
    } finally {
      this.loggingOut.set(false);
    }
  }
}

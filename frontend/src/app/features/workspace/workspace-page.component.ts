import {Component, DestroyRef, inject, OnInit, signal} from '@angular/core';
import {Router} from '@angular/router';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';

@Component({
  selector: 'app-workspace-page',
  standalone: true,
  styleUrl: './workspace-page.component.css',
  template: `
    <main class="auth-wrapper">
      <header class="auth-brand">
        <div>agentos</div>
        <p>Không gian làm việc cho đội ngũ AI</p>
      </header>
      <section
        class="auth-card"
        aria-labelledby="workspace-title"
        [attr.aria-busy]="busy()"
      >
        <header class="auth-login-heading">
          <h1 id="workspace-title">
            {{
              !loading() && !loadError() && !workspaces().length
                ? 'Bạn chưa có workspace'
                : 'Chọn workspace'
            }}
          </h1>
        </header>
        <p class="auth-subtitle workspace-identity">
          Đã đăng nhập với {{ auth.user()?.email }}
        </p>
        @if (loading()) {
          <p class="auth-subtitle" role="status">
            Đang kiểm tra quyền truy cập…
          </p>
        } @else if (loadError()) {
          <p class="auth-error" role="alert">{{ loadError() }}</p>
          <button
            class="button secondary auth-action"
            (click)="load()"
            type="button"
            [disabled]="busy()"
          >
            Thử lại
          </button>
        } @else if (workspaces().length) {
          <ul class="workspace-list">
            @for (workspace of workspaces(); track workspace.id) {
              <li class="workspace-option">
                <div class="workspace-membership">
                  <h2>{{ workspace.name }}</h2>
                  <p>{{ roleLabel(workspace.role) }}</p>
                  <p>Quyền truy cập áp dụng trong phạm vi workspace này.</p>
                </div>
                <button
                  class="button auth-action"
                  type="button"
                  [disabled]="busy()"
                  (click)="open(workspace.id)"
                >
                  {{
                    opening() === workspace.id
                      ? 'Đang mở…'
                      : 'Vào ' + workspace.name
                  }}
                </button>
              </li>
            }
          </ul>
        } @else {
          <p class="auth-subtitle">
            Tài khoản của bạn chưa có tư cách thành viên đang hoạt động trong
            workspace nào.
          </p>
          <p class="workspace-notice workspace-warning" role="status">
            Liên hệ quản trị viên tổ chức để được cấp quyền truy cập.
          </p>
          <button
            class="button auth-action"
            type="button"
            [disabled]="busy()"
            (click)="load()"
          >
            Kiểm tra lại quyền truy cập
          </button>
        }
        @if (actionError()) {
          <p class="auth-error" role="alert">{{ actionError() }}</p>
        }
        <button
          class="button secondary auth-action"
          type="button"
          [disabled]="busy()"
          (click)="logout()"
        >
          {{
            loggingOut()
              ? 'Đang đăng xuất…'
              : workspaces().length
                ? 'Đăng xuất'
                : 'Đổi tài khoản'
          }}
        </button>
      </section>
    </main>
  `,
})
export class WorkspacePageComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly workspaces = signal<Workspace[]>([]);
  readonly loading = signal(true);
  readonly loggingOut = signal(false);
  readonly opening = signal<string | null>(null);
  readonly loadError = signal('');
  readonly actionError = signal('');
  busy(): boolean {
    return this.loading() || this.loggingOut() || this.opening() !== null;
  }
  roleLabel(role: string): string {
    return role === 'ADMIN'
      ? 'Quản trị viên tổ chức'
      : role === 'MEMBER'
        ? 'Thành viên tổ chức'
        : role;
  }
  ngOnInit(): void {
    void this.load();
  }
  async load(): Promise<void> {
    if (this.loggingOut() || this.opening()) return;
    this.loading.set(true);
    this.loadError.set('');
    this.actionError.set('');
    try {
      const workspaces = await this.auth.workspaces();
      if (!this.destroyRef.destroyed) this.workspaces.set(workspaces);
    } catch (error) {
      if (this.destroyRef.destroyed) return;
      if (error instanceof AuthFailure && error.kind === 'unauthorized')
        await this.router.navigateByUrl('/login');
      else
        this.loadError.set(
          error instanceof AuthFailure && error.kind === 'network'
            ? 'Không thể kết nối. Kiểm tra mạng và thử lại.'
            : 'Không thể tải workspace. Vui lòng thử lại.',
        );
    } finally {
      if (!this.destroyRef.destroyed) this.loading.set(false);
    }
  }
  async open(id: string): Promise<void> {
    if (this.busy()) return;
    this.opening.set(id);
    this.actionError.set('');
    try {
      await this.router.navigate(['/workspace', id]);
    } catch {
      if (!this.destroyRef.destroyed)
        this.actionError.set('Không thể mở workspace. Vui lòng thử lại.');
    } finally {
      if (!this.destroyRef.destroyed) this.opening.set(null);
    }
  }
  async logout(): Promise<void> {
    if (this.busy()) return;
    this.loggingOut.set(true);
    this.actionError.set('');
    try {
      await this.auth.logout();
      if (!this.destroyRef.destroyed) await this.router.navigateByUrl('/login');
    } catch (error) {
      if (this.destroyRef.destroyed) return;
      if (error instanceof AuthFailure && error.kind === 'unauthorized')
        await this.router.navigateByUrl('/login');
      else this.actionError.set('Không thể đăng xuất. Vui lòng thử lại.');
    } finally {
      if (!this.destroyRef.destroyed) this.loggingOut.set(false);
    }
  }
}

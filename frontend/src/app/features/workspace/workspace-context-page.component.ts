import {Component, DestroyRef, inject, OnInit, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';

@Component({
  selector: 'app-workspace-context-page',
  standalone: true,
  imports: [RouterLink],
  template: `
    <main class="auth-wrapper">
      <header class="auth-brand">
        <div>agentos</div>
        <p>Không gian làm việc cho đội ngũ AI</p>
      </header>
      <section
        class="auth-card"
        aria-labelledby="context-title"
        [attr.aria-busy]="loading()"
      >
        <header>
          <h1 id="context-title">
            {{
              loading()
                ? 'Đang mở workspace'
                : lostAccess()
                  ? 'Không còn quyền truy cập'
                  : workspace()?.name || 'Không thể mở workspace'
            }}
          </h1>
          <p class="auth-subtitle">Đã đăng nhập với {{ auth.user()?.email }}</p>
        </header>
        @if (loading()) {
          <p class="auth-subtitle" role="status">
            Đang xác thực quyền truy cập workspace…
          </p>
        } @else if (lostAccess()) {
          <p class="auth-error" role="alert">
            Workspace không tồn tại hoặc bạn không còn tư cách thành viên đang
            hoạt động. Liên hệ quản trị viên tổ chức nếu cần quyền truy cập.
          </p>
        } @else if (error()) {
          <p class="auth-error" role="alert">{{ error() }}</p>
          <button
            class="button auth-action"
            type="button"
            [disabled]="loading()"
            (click)="load()"
          >
            Thử lại
          </button>
        } @else if (workspace(); as context) {
          <p class="auth-subtitle">{{ roleLabel(context.role) }}</p>
          <p class="auth-small">
            Quyền truy cập hiện tại đã được xác thực trong phạm vi workspace
            này.
          </p>
          <p class="auth-subtitle">
            Dashboard và các mô-đun nghiệp vụ đang được phát triển.
          </p>
        }
        <a class="button secondary auth-action" routerLink="/workspace"
          >Quay lại chọn workspace</a
        >
      </section>
    </main>
  `,
})
export class WorkspaceContextPageComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private requestVersion = 0;
  readonly loading = signal(true);
  readonly workspace = signal<Workspace | null>(null);
  readonly lostAccess = signal(false);
  readonly error = signal('');
  ngOnInit(): void {
    this.route.paramMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        void this.load();
      });
  }
  roleLabel(role: string): string {
    return role === 'ADMIN'
      ? 'Quản trị viên tổ chức'
      : role === 'MEMBER'
        ? 'Thành viên tổ chức'
        : role;
  }
  async load(): Promise<void> {
    const version = ++this.requestVersion;
    const active = () =>
      !this.destroyRef.destroyed && version === this.requestVersion;
    this.loading.set(true);
    this.workspace.set(null);
    this.error.set('');
    this.lostAccess.set(false);
    const id = this.route.snapshot.paramMap.get('id');
    try {
      if (!id) {
        this.lostAccess.set(true);
        return;
      }
      const workspace = await this.auth.getWorkspace(id);
      if (active()) this.workspace.set(workspace);
    } catch (error) {
      if (!active()) return;
      if (error instanceof AuthFailure && error.kind === 'unauthorized')
        await this.router.navigateByUrl('/login');
      else if (error instanceof AuthFailure && error.kind === 'not-found')
        this.lostAccess.set(true);
      else
        this.error.set(
          error instanceof AuthFailure && error.kind === 'network'
            ? 'Không thể kết nối. Kiểm tra mạng và thử lại.'
            : 'Không thể xác thực quyền truy cập workspace. Vui lòng thử lại.',
        );
    } finally {
      if (active()) this.loading.set(false);
    }
  }
}

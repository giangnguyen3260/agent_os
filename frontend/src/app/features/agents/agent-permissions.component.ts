import {HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  effect,
  inject,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {
  AgentPermissions,
  AgentPermissionsService,
  permissionsUuid,
  validAgentPermissions,
  validPermissionsWorkspace,
} from './agent-permissions.service';
@Component({
  selector: 'app-agent-permissions',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './agent-permissions.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './agent-permissions.component.css',
  ],
})
export class AgentPermissionsComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(AgentPermissionsService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  id = '';
  agentId = '';
  readonly context = signal<Workspace | null>(null);
  readonly permissions = signal<AgentPermissions | null>(null);
  readonly loading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly menuOpen = signal(false);
  readonly labels = {MODEL: 'Mô hình', TOOLS: 'Công cụ', SKILLS: 'Kỹ năng'};
  constructor() {
    effect(() => {
      const actor = this.auth.user()?.id;
      if (actor !== this.account) {
        this.account = actor;
        this.clear();
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => this.clear());
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.clear();
      this.id = params.get('id') || '';
      this.agentId = params.get('agentId') || '';
      void this.load();
    });
  }
  configured(value: string | null): string {
    return value === null
      ? 'Chưa cấu hình'
      : value === ''
        ? 'Chưa nhập nội dung'
        : value;
  }
  private clear(): void {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.permissions.set(null);
    this.context.set(null);
    this.loading.set(false);
    this.error.set('');
    this.closeMenu();
  }
  private active(epoch: number, actor: string | undefined): boolean {
    return (
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      !!actor &&
      actor === this.auth.user()?.id
    );
  }
  @HostListener('window:focus')
  revalidate(): Promise<void> {
    return this.load();
  }
  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (document.visibilityState === 'visible') void this.load();
  }
  async load(): Promise<void> {
    this.clear();
    this.denied.set(false);
    if (!permissionsUuid(this.id) || !permissionsUuid(this.agentId)) {
      this.error.set('Đường dẫn không hợp lệ.');
      return;
    }
    const epoch = this.epoch;
    const actor = this.auth.user()?.id;
    this.loading.set(true);
    try {
      const session = await this.auth.session();
      if (!this.active(epoch, actor)) return;
      if (session.id !== actor) {
        this.clear();
        this.denied.set(true);
        return;
      }
      const workspace = await this.api.workspace(this.id);
      if (!this.active(epoch, actor)) return;
      if (!validPermissionsWorkspace(workspace?.data, this.id)) {
        this.clear();
        this.error.set('Dữ liệu workspace không hợp lệ. Vui lòng thử lại.');
        return;
      }
      const context = workspace.data;
      this.requests.add(
        this.api.read(this.id, this.agentId).subscribe({
          next: response => {
            if (!this.active(epoch, actor)) return;
            if (!validAgentPermissions(response?.data, this.id, this.agentId)) {
              this.clear();
              this.error.set('Dữ liệu không hợp lệ. Vui lòng thử lại.');
              return;
            }
            this.context.set(context);
            this.permissions.set(response.data);
            this.loading.set(false);
          },
          error: error => {
            if (this.active(epoch, actor)) this.failure(error);
          },
        }),
      );
    } catch (error) {
      if (this.active(epoch, actor)) this.failure(error);
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
    else this.error.set('Không thể tải thông tin quyền. Vui lòng thử lại.');
  }
  toggleMenu(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#agent-permissions-menu')
      ?.showModal();
  }
  closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#agent-permissions-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="agent-permissions-menu"]')
      ?.focus();
  }
}

import {DatePipe, NgTemplateOutlet} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {
  Dashboard,
  DashboardService,
  DashboardTask,
  Page,
  TaskState,
} from './dashboard.service';

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  imports: [RouterLink, DatePipe, NgTemplateOutlet, FormsModule],
  templateUrl: './dashboard-page.component.html',
  styleUrl: './dashboard-page.component.css',
})
export class DashboardPageComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(DashboardService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private version = 0;
  private taskVersion = 0;
  private dashboardRequest?: Subscription;
  private taskRequest?: Subscription;
  private id = '';
  readonly context = signal<Workspace | null>(null);
  readonly data = signal<Dashboard | null>(null);
  readonly tasks = signal<Page<DashboardTask> | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly accessLost = signal(false);
  readonly taskLoading = signal(false);
  readonly taskError = signal('');
  readonly state = signal<TaskState | null>(null);
  readonly teamId = signal('');
  readonly teamName = signal('');
  readonly teamPage = signal(0);
  readonly taskPage = signal(0);
  readonly signingOut = signal(false);
  readonly logoutError = signal('');
  readonly menu = viewChild<ElementRef<HTMLDialogElement>>('menu');
  readonly teamsSection = viewChild<ElementRef<HTMLElement>>('teamsSection');
  readonly taskSection = viewChild<ElementRef<HTMLElement>>('taskSection');
  readonly states: TaskState[] = ['RUNNING', 'PENDING', 'REVIEW'];
  readonly modules = ['Agents', 'Missions', 'Approvals', 'Trace'];
  ngOnInit(): void {
    this.destroy.onDestroy(() => {
      this.version++;
      this.cancelTasks();
      this.dashboardRequest?.unsubscribe();
    });
    this.route.paramMap
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe(params => {
        this.id = params.get('id') || '';
        this.context.set(null);
        this.data.set(null);
        this.state.set(null);
        this.teamId.set('');
        this.teamName.set('');
        this.teamPage.set(0);
        this.cancelTasks();
        this.menu()?.nativeElement.close();
        void this.load();
      });
  }
  private cancelTasks(): void {
    this.taskVersion++;
    this.taskRequest?.unsubscribe();
    this.tasks.set(null);
    this.taskError.set('');
    this.taskLoading.set(false);
  }
  async load(page = this.teamPage()): Promise<void> {
    const version = ++this.version;
    this.dashboardRequest?.unsubscribe();
    this.cancelTasks();
    this.loading.set(true);
    this.data.set(null);
    this.context.set(null);
    this.error.set('');
    this.accessLost.set(false);
    try {
      const context = await this.auth.getWorkspace(this.id);
      if (version !== this.version || this.destroy.destroyed) return;
      this.context.set(context);
      this.dashboardRequest = this.api.dashboard(this.id, page).subscribe({
        next: data => {
          if (version !== this.version) return;
          this.data.set(data);
          this.teamPage.set(data.teams.page);
          this.loading.set(false);
          if (this.state()) this.loadTasks();
        },
        error: error => {
          if (version === this.version) {
            this.loading.set(false);
            this.handleError(error);
          }
        },
      });
    } catch (error) {
      if (version !== this.version || this.destroy.destroyed) return;
      this.loading.set(false);
      this.handleError(error);
    }
  }
  private handleError(error: unknown, task = false): void {
    const status = error instanceof HttpErrorResponse ? error.status : 0;
    if (
      status === 401 ||
      (error instanceof AuthFailure && error.kind === 'unauthorized')
    ) {
      this.clearAccess();
      void this.router.navigateByUrl('/login');
    } else if (
      status === 403 ||
      (!task &&
        (status === 404 ||
          (error instanceof AuthFailure && error.kind === 'not-found')))
    ) {
      this.clearAccess();
      this.accessLost.set(true);
    } else if (task) {
      this.taskError.set(
        status === 404
          ? 'Team không tồn tại hoặc không còn khả dụng. Chọn tất cả teams để thử lại.'
          : 'Không tải được task. Vui lòng thử lại.',
      );
    } else {
      this.error.set('Không tải được tổng quan. Kiểm tra kết nối và thử lại.');
    }
  }
  private clearAccess(): void {
    this.version++;
    this.dashboardRequest?.unsubscribe();
    this.cancelTasks();
    this.data.set(null);
    this.context.set(null);
    this.state.set(null);
  }
  openTasks(state: TaskState, teamId = '', teamName = ''): void {
    this.state.set(state);
    this.teamId.set(teamId);
    this.teamName.set(teamName);
    this.taskPage.set(0);
    this.loadTasks();
    setTimeout(() => {
      if (!this.destroy.destroyed)
        this.focus(this.taskSection()?.nativeElement);
    });
  }
  changeState(value: string): void {
    if (this.states.includes(value as TaskState))
      this.openTasks(value as TaskState, this.teamId(), this.teamName());
  }
  changeTeam(value: string): void {
    const name =
      this.data()?.teams.items.find(team => team.id === value)?.name ||
      this.teamName();
    this.openTasks(this.state() || 'RUNNING', value, value ? name : '');
  }
  loadTasks(page = this.taskPage()): void {
    const state = this.state();
    if (!state || !this.context() || !this.data()) return;
    this.cancelTasks();
    const version = this.taskVersion;
    this.taskPage.set(page);
    this.taskLoading.set(true);
    this.taskRequest = this.api
      .tasks(this.id, state, this.teamId(), page)
      .subscribe({
        next: tasks => {
          if (version === this.taskVersion) {
            this.tasks.set(tasks);
            this.taskLoading.set(false);
          }
        },
        error: error => {
          if (version === this.taskVersion) {
            this.taskLoading.set(false);
            this.handleError(error, true);
          }
        },
      });
  }
  closeTasks(): void {
    this.cancelTasks();
    this.state.set(null);
  }
  createAgent(): void {
    if (this.context()?.role === 'ADMIN')
      void this.router.navigate(['/workspace', this.id, 'agents', 'new']);
  }
  createTeam(): void {
    if (this.context()?.role === 'ADMIN')
      void this.router.navigate(['/workspace', this.id, 'teams', 'new']);
  }
  viewTeams(): void {
    this.menu()?.nativeElement.close();
    this.focus(this.teamsSection()?.nativeElement);
  }
  private focus(element?: HTMLElement): void {
    element?.focus();
    element?.scrollIntoView({behavior: 'smooth', block: 'start'});
  }
  label(state: string): string {
    return state === 'RUNNING'
      ? 'Running'
      : state === 'PENDING'
        ? 'Pending'
        : 'Review';
  }
  health(value: string): string {
    return (
      (
        {
          ARCHIVED: 'Archived',
          SETUP_REQUIRED: 'Setup required',
          ATTENTION: 'Attention',
          NORMAL: 'Normal',
        } as Record<string, string>
      )[value] || value
    );
  }
  role(value: string): string {
    return value === 'ADMIN'
      ? 'Quản trị viên tổ chức'
      : value === 'MEMBER'
        ? 'Thành viên tổ chức'
        : value;
  }
  async logout(): Promise<void> {
    this.signingOut.set(true);
    this.logoutError.set('');
    try {
      await this.auth.logout();
      this.clearAccess();
      await this.router.navigateByUrl('/login');
    } catch {
      this.logoutError.set('Đăng xuất chưa thành công. Vui lòng thử lại.');
    } finally {
      this.signingOut.set(false);
    }
  }
}

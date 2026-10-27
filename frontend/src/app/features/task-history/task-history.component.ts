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
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {combineLatest, Subject} from 'rxjs';
import {AuthFailure, AuthService} from '../../core/auth/auth.service';
import {TaskEditService} from '../task-edit/task-edit.service';
import {
  exact,
  fields,
  labels,
  Scope,
  uuid,
} from '../task-edit/task-edit-validation';
import {HistoryPage, pageNumber, validHistory} from './task-history-validation';

@Component({
  selector: 'app-task-history',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './task-history.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../agents/agent-studio-page.component.css',
    './task-history.component.css',
  ],
})
export class TaskHistoryComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(TaskEditService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly cancel = new Subject<void>();
  private epoch = 0;
  private actor = this.auth.user()?.id;
  private invalid = false;
  id = '';
  teamId = '';
  missionId = '';
  taskId = '';
  page = 0;
  readonly history = signal<HistoryPage | null>(null);
  readonly workspaceName = signal('');
  readonly loading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly menuOpen = signal(false);
  readonly fields = fields;
  readonly labels = labels;

  constructor() {
    effect(() => {
      const actor = this.auth.user()?.id;
      if (actor !== this.actor) {
        this.actor = actor;
        this.clear();
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => {
      this.clear();
      this.cancel.complete();
    });
    combineLatest([this.route.paramMap, this.route.queryParamMap])
      .pipe(takeUntilDestroyed())
      .subscribe(([p, q]) => {
        this.clear();
        for (const k of ['id', 'teamId', 'missionId', 'taskId'] as const)
          this[k] = p.get(k) ?? '';
        this.page = pageNumber(q.get('page') ?? '0');
        this.invalid = q.keys.some(
          k => k !== 'page' || q.getAll(k).length !== 1,
        );
        this.actor = this.auth.user()?.id;
        void this.load();
      });
  }
  scope(): Scope {
    return {
      organizationId: this.id,
      teamId: this.teamId,
      missionId: this.missionId,
      taskId: this.taskId,
    };
  }
  detailLink() {
    return [
      '/workspace',
      this.id,
      'teams',
      this.teamId,
      'missions',
      this.missionId,
      'tasks',
      this.taskId,
    ];
  }
  private clear() {
    this.epoch++;
    this.cancel.next();
    this.history.set(null);
    this.workspaceName.set('');
    this.loading.set(false);
    this.error.set('');
  }
  private active(epoch: number, actor: string | undefined) {
    return (
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      actor === this.auth.user()?.id
    );
  }
  async load() {
    this.clear();
    if (this.destroy.destroyed) return;
    this.denied.set(false);
    if (
      this.invalid ||
      this.page < 0 ||
      !Object.values(this.scope()).every(uuid)
    ) {
      this.error.set('Địa chỉ task hoặc trang lịch sử không hợp lệ.');
      return;
    }
    const epoch = this.epoch,
      actor = this.auth.user()?.id,
      scope = this.scope(),
      page = this.page;
    this.loading.set(true);
    try {
      if (!uuid(actor)) {
        this.denied.set(true);
        return;
      }
      const session = await this.auth.session();
      if (!this.active(epoch, actor)) return;
      if (
        !exact(session, 'id email displayName') ||
        session['id'] !== actor ||
        typeof session['email'] !== 'string' ||
        typeof session['displayName'] !== 'string'
      ) {
        this.denied.set(true);
        return;
      }
      const workspace = await this.api.workspace(
        scope.organizationId,
        this.cancel,
      );
      if (!this.active(epoch, actor)) return;
      if (
        !exact(workspace, 'id name role') ||
        workspace['id'] !== scope.organizationId ||
        typeof workspace['name'] !== 'string'
      )
        throw Error('Invalid workspace');
      if (workspace['role'] !== 'ADMIN') {
        this.denied.set(true);
        return;
      }
      const url = `/api/v1/organizations/${scope.organizationId}/teams/${scope.teamId}/missions/${scope.missionId}/tasks/${scope.taskId}/draft-settings/history?page=${page}`;
      const data = await this.api.get(url, this.cancel);
      if (!this.active(epoch, actor)) return;
      if (!validHistory(data, scope, page)) throw Error('Invalid history');
      this.workspaceName.set(workspace['name']);
      this.history.set(data);
    } catch (error) {
      if (!this.active(epoch, actor)) return;
      this.clear();
      if (
        (error instanceof HttpErrorResponse &&
          [401, 403, 404].includes(error.status)) ||
        (error instanceof AuthFailure &&
          ['unauthorized', 'not-found'].includes(error.kind))
      )
        this.denied.set(true);
      else this.error.set('Không thể tải hoặc xác minh lịch sử chỉnh sửa.');
    } finally {
      if (this.active(epoch, actor)) this.loading.set(false);
    }
  }
  @HostListener('window:focus') onFocus() {
    void this.load();
  }
  @HostListener('document:visibilitychange') onVisibility() {
    if (document.visibilityState === 'visible') void this.load();
    else this.clear();
  }
  changePage(delta: number) {
    const history = this.history(),
      next = this.page + delta;
    if (
      !history ||
      this.loading() ||
      ![-1, 1].includes(delta) ||
      next < 0 ||
      next > 100000 ||
      (delta > 0 && next * 20 >= history.total)
    )
      return;
    this.clear();
    void this.router
      .navigate([], {relativeTo: this.route, queryParams: {page: next}})
      .then(ok => {
        if (!ok && !this.destroy.destroyed) void this.load();
      });
  }
  show(value: string | null) {
    return value === null ? 'Chưa có dữ liệu' : value;
  }
  toggleMenu() {
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('dialog')
      ?.showModal();
  }
  closeMenu() {
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('dialog')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="task-history-menu"]')
      ?.focus();
  }
}

import {HttpErrorResponse} from '@angular/common/http';
import {DatePipe} from '@angular/common';
import {
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {combineLatest, Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
import {
  Activity,
  Page,
  MissionSnapshot,
  Task,
  TaskStatus,
  MissionWorkspaceService,
  taskStatuses,
  validActivity,
  validPage,
  validSnapshot,
  validTask,
} from './mission-workspace.service';

@Component({
  selector: 'app-mission-workspace',
  standalone: true,
  imports: [RouterLink, FormsModule, DatePipe],
  templateUrl: './mission-workspace.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../teams/team-workspace.component.css',
    './mission-workspace.component.css',
  ],
})
export class MissionWorkspaceComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(MissionWorkspaceService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  private invalid = false;
  id = '';
  teamId = '';
  missionId = '';
  tab = 'tasks';
  status: TaskStatus = 'ALL';
  filter: TaskStatus = 'ALL';
  page = 0;
  readonly tabs = ['tasks', 'pending', 'activity'];
  readonly statuses = taskStatuses;
  readonly context = signal<Workspace | null>(null);
  readonly snapshot = signal<MissionSnapshot | null>(null);
  readonly tasks = signal<Page<Task> | null>(null);
  readonly activity = signal<Page<Activity> | null>(null);
  readonly loading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly menuOpen = signal(false);
  constructor() {
    effect(() => {
      const account = this.auth.user()?.id;
      if (account !== this.account) {
        this.account = account;
        this.clear();
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => this.clear());
    combineLatest([this.route.paramMap, this.route.queryParamMap])
      .pipe(takeUntilDestroyed())
      .subscribe(([params, q]) => {
        this.id = params.get('id') || '';
        this.teamId = params.get('teamId') || '';
        this.missionId = params.get('missionId') || '';
        this.tab = q.get('tab') ?? 'tasks';
        const raw = q.get('page') ?? '0';
        this.page = /^(0|[1-9]\d*)$/.test(raw) ? Number(raw) : -1;
        this.status = (q.get('status') ??
          (this.tab === 'pending' ? 'PENDING' : 'ALL')) as TaskStatus;
        this.filter = this.status;
        this.invalid =
          q.keys.some(
            key =>
              !['tab', 'status', 'page'].includes(key) ||
              q.getAll(key).length !== 1,
          ) ||
          !this.tabs.includes(this.tab) ||
          !taskStatuses.includes(this.status) ||
          (this.tab !== 'tasks' && q.has('status'));
        this.load();
      });
  }
  private clear() {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.snapshot.set(null);
    this.tasks.set(null);
    this.activity.set(null);
    this.context.set(null);
    this.loading.set(false);
    this.error.set('');
    this.closeMenu();
  }
  load() {
    this.clear();
    this.denied.set(false);
    if (
      this.invalid ||
      !uuid(this.id) ||
      !uuid(this.teamId) ||
      !uuid(this.missionId) ||
      !Number.isSafeInteger(this.page) ||
      this.page < 0 ||
      this.page > 100000
    ) {
      this.error.set('Invalid workspace filters. Reset filters to continue.');
      return;
    }
    const epoch = this.epoch,
      account = this.auth.user()?.id;
    const active = () =>
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      account === this.auth.user()?.id;
    this.loading.set(true);
    this.requests.add(
      this.api.authorize(this.id).subscribe({
        next: authorization => {
          const context = authorization?.data;
          if (!active()) return;
          if (
            !account ||
            !context ||
            context.id !== this.id ||
            !['ADMIN', 'MEMBER'].includes(context.role)
          ) {
            this.clear();
            this.denied.set(true);
            return;
          }
          this.requests.add(
            this.api.summary(this.id, this.teamId, this.missionId).subscribe({
              next: response => {
                if (!active()) return;
                if (
                  !validSnapshot(
                    response?.data,
                    this.id,
                    this.teamId,
                    this.missionId,
                  )
                ) {
                  this.failure();
                  return;
                }
                this.context.set(context);
                this.snapshot.set(response.data);
                if (this.tab === 'activity')
                  this.requests.add(
                    this.api
                      .activity(this.id, this.teamId, this.missionId, this.page)
                      .subscribe({
                        next: r => {
                          if (!active()) return;
                          if (!validPage(r?.data, this.page, validActivity)) {
                            this.failure();
                            return;
                          }
                          this.activity.set(r.data);
                          this.loading.set(false);
                        },
                        error: e => {
                          if (active()) this.failure(e);
                        },
                      }),
                  );
                else {
                  this.requests.add(
                    this.api
                      .tasks(
                        this.id,
                        this.teamId,
                        this.missionId,
                        this.status,
                        this.page,
                      )
                      .subscribe({
                        next: r => {
                          if (!active()) return;
                          if (
                            !validPage(r?.data, this.page, t =>
                              validTask(
                                t,
                                this.id,
                                this.teamId,
                                this.missionId,
                                this.status,
                              ),
                            )
                          ) {
                            this.failure();
                            return;
                          }
                          this.tasks.set(r.data);
                          this.loading.set(false);
                        },
                        error: e => {
                          if (active()) this.failure(e);
                        },
                      }),
                  );
                }
              },
              error: e => {
                if (active()) this.failure(e);
              },
            }),
          );
        },
        error: e => {
          if (active()) this.failure(e);
        },
      }),
    );
  }
  private failure(error?: unknown) {
    this.clear();
    if (
      (error instanceof HttpErrorResponse &&
        [401, 403, 404].includes(error.status)) ||
      (error instanceof AuthFailure &&
        ['unauthorized', 'not-found'].includes(error.kind))
    )
      this.denied.set(true);
    else
      this.error.set(
        'Unable to load current workspace data. Please try again.',
      );
  }
  navigate(tab: string, status: TaskStatus = 'ALL', page = 0) {
    void this.router.navigate(
      [
        '/workspace',
        this.id,
        'teams',
        this.teamId,
        'missions',
        this.missionId,
        'workspace',
      ],
      {
        queryParams: {
          tab,
          ...(tab === 'tasks' && status !== 'ALL' ? {status} : {}),
          ...(page ? {page} : {}),
        },
      },
    );
  }
  changePage(page: number) {
    this.navigate(this.tab, this.status, page);
  }
  label(value: string) {
    return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
  }
  toggleMenu() {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#mission-workspace-menu')
      ?.showModal();
  }
  closeMenu() {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#mission-workspace-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="mission-workspace-menu"]')
      ?.focus();
  }
}

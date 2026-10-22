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
  validActivity,
  validPage,
} from './mission-workspace.service';
import {
  TaskDetail,
  TaskDetailService,
  validDetail,
} from './task-detail.service';

@Component({
  selector: 'app-task-detail',
  standalone: true,
  imports: [RouterLink, DatePipe],
  templateUrl: './task-detail.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../teams/team-workspace.component.css',
    './mission-workspace.component.css',
    './task-detail.component.css',
  ],
})
export class TaskDetailComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(TaskDetailService);
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
  taskId = '';
  page = 0;
  readonly context = signal<Workspace | null>(null);
  readonly snapshot = signal<TaskDetail | null>(null);
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
        this.taskId = params.get('taskId') || '';
        const raw = q.get('page') ?? '0';
        this.page = /^(0|[1-9]\d*)$/.test(raw) ? Number(raw) : -1;
        this.invalid = q.keys.some(
          key => key !== 'page' || q.getAll(key).length !== 1,
        );
        this.load();
      });
  }
  private clear() {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.snapshot.set(null);
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
      !uuid(this.taskId) ||
      !Number.isSafeInteger(this.page) ||
      this.page < 0 ||
      this.page > 100000
    ) {
      this.error.set('Invalid task address or activity page.');
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
            this.api
              .get(this.id, this.teamId, this.missionId, this.taskId)
              .subscribe({
                next: response => {
                  if (!active()) return;
                  if (
                    !validDetail(
                      response?.data,
                      this.id,
                      this.teamId,
                      this.missionId,
                      this.taskId,
                    )
                  ) {
                    this.failure();
                    return;
                  }
                  this.context.set(context);
                  this.snapshot.set(response.data);
                  this.requests.add(
                    this.api
                      .activity(
                        this.id,
                        this.teamId,
                        this.missionId,
                        this.taskId,
                        this.page,
                      )
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
    else this.error.set('Unable to load current task data. Please try again.');
  }
  changePage(page: number) {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: page ? {page} : {},
    });
  }
  toggleMenu() {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#task-detail-menu')
      ?.showModal();
  }
  closeMenu() {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#task-detail-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="task-detail-menu"]')
      ?.focus();
  }
}

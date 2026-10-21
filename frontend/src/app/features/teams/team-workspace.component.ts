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
import {
  AgentDirectoryService,
  DirectoryPage,
  validDirectory,
} from '../agents/agent-directory.service';
import {uuid} from './team.service';
import {
  Activity,
  Lane,
  Page,
  Snapshot,
  Task,
  TeamWorkspaceService,
  lanes,
  validActivity,
  validPage,
  validSnapshot,
  validTask,
} from './team-workspace.service';

@Component({
  selector: 'app-team-workspace',
  standalone: true,
  imports: [RouterLink, FormsModule, DatePipe],
  templateUrl: './team-workspace.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './team-workspace.component.css',
  ],
})
export class TeamWorkspaceComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(TeamWorkspaceService);
  private readonly directory = inject(AgentDirectoryService);
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
  tab = 'overview';
  lane: Lane | null = null;
  page = 0;
  query = '';
  search = '';
  readonly tabs = ['overview', 'pending', 'agents', 'activity'];
  readonly context = signal<Workspace | null>(null);
  readonly snapshot = signal<Snapshot | null>(null);
  readonly tasks = signal<Page<Task> | null>(null);
  readonly agents = signal<DirectoryPage | null>(null);
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
        this.tab = q.get('tab') ?? 'overview';
        const raw = q.get('page') ?? '0';
        this.page = /^(0|[1-9]\d*)$/.test(raw) ? Number(raw) : -1;
        this.lane = q.get('lane') as Lane | null;
        this.query = q.get('query') ?? '';
        this.search = this.query;
        this.invalid =
          q.keys.some(
            key =>
              !['tab', 'lane', 'page', 'query'].includes(key) ||
              q.getAll(key).length !== 1,
          ) ||
          !this.tabs.includes(this.tab) ||
          (this.lane !== null && !lanes.includes(this.lane)) ||
          (this.tab === 'pending' &&
            this.lane !== null &&
            this.lane !== 'PENDING') ||
          (['agents', 'activity'].includes(this.tab) && this.lane !== null) ||
          (this.tab !== 'agents' && q.has('query')) ||
          (this.tab === 'overview' && !this.lane && q.has('page'));
        void this.load();
      });
  }
  private clear() {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.snapshot.set(null);
    this.tasks.set(null);
    this.agents.set(null);
    this.activity.set(null);
    this.context.set(null);
    this.loading.set(false);
    this.error.set('');
    this.closeMenu();
  }
  async load() {
    this.clear();
    this.denied.set(false);
    if (
      this.invalid ||
      !uuid(this.id) ||
      !uuid(this.teamId) ||
      !Number.isSafeInteger(this.page) ||
      this.page < 0 ||
      this.page > 100000 ||
      this.query.length > 200
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
    try {
      const context = await this.auth.getWorkspace(this.id);
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
        this.api.summary(this.id, this.teamId).subscribe({
          next: response => {
            if (!active()) return;
            if (!validSnapshot(response?.data, this.id, this.teamId)) {
              this.failure();
              return;
            }
            this.context.set(context);
            this.snapshot.set(response.data);
            if (this.tab === 'agents')
              this.requests.add(
                this.directory
                  .list(this.id, this.page, this.query, '', this.teamId)
                  .subscribe({
                    next: r => {
                      if (!active()) return;
                      if (
                        !validDirectory(r?.data, this.id, this.page) ||
                        !r.data.items.every(a => a.teamId === this.teamId)
                      ) {
                        this.failure();
                        return;
                      }
                      this.agents.set(r.data);
                      this.loading.set(false);
                    },
                    error: e => {
                      if (active()) this.failure(e);
                    },
                  }),
              );
            else if (this.tab === 'activity')
              this.requests.add(
                this.api.activity(this.id, this.teamId, this.page).subscribe({
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
            else if (this.tab === 'pending' || this.lane) {
              const lane = this.lane ?? 'PENDING';
              this.requests.add(
                this.api
                  .tasks(this.id, this.teamId, lane, this.page)
                  .subscribe({
                    next: r => {
                      if (!active()) return;
                      if (
                        !validPage(r?.data, this.page, t =>
                          validTask(t, this.id, this.teamId, lane),
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
            } else this.loading.set(false);
          },
          error: e => {
            if (active()) this.failure(e);
          },
        }),
      );
    } catch (e) {
      if (active()) this.failure(e);
    }
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
  navigate(tab: string, lane: Lane | null = null, page = 0, query = '') {
    void this.router.navigate(
      ['/workspace', this.id, 'teams', this.teamId, 'workspace'],
      {
        queryParams: {
          tab,
          ...(lane ? {lane} : {}),
          ...(page ? {page} : {}),
          ...(query ? {query} : {}),
        },
      },
    );
  }
  openLane(lane: Lane) {
    this.navigate(lane === 'PENDING' ? 'pending' : 'overview', lane);
  }
  changePage(page: number) {
    this.navigate(this.tab, this.lane, page, this.query);
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
      .querySelector<HTMLDialogElement>('#team-workspace-menu')
      ?.showModal();
  }
  closeMenu() {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#team-workspace-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="team-workspace-menu"]')
      ?.focus();
  }
}

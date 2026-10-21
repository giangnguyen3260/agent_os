import {HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  effect,
  inject,
  OnInit,
  signal,
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
  Candidate,
  CandidatePage,
  uuid,
  TeamService,
} from '../teams/team.service';
import {
  MissionBody,
  MissionDetail,
  MissionService,
  validMission,
  validDate,
} from './mission.service';
import {validPage} from '../teams/team-workspace.service';

@Component({
  selector: 'app-mission-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './mission-page.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './mission-page.component.css',
  ],
})
export class MissionPageComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(MissionService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private finished = false;
  private version = 0;
  private candidateVersion = 0;
  private requests = new Subscription();
  private candidateRequest?: Subscription;
  private attempt: {key: string; body: MissionBody} | null = null;
  private identity = this.auth.user()?.id;
  id = '';
  teamId = '';
  missionId = '';
  readonly teamApi = inject(TeamService);
  readonly teamName = signal('');
  readonly context = signal<Workspace | null>(null);
  readonly loading = signal(true);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly saved = signal<MissionDetail | null>(null);
  readonly busy = signal(false);
  readonly frozen = signal(false);
  readonly keyConflict = signal(false);
  readonly candidates = signal<CandidatePage | null>(null);
  readonly candidateLoading = signal(false);
  readonly candidateError = signal('');
  readonly known = signal<Record<string, Candidate>>({});
  readonly menuOpen = signal(false);
  step = 1;
  title = '';
  goal = '';
  requiredOutput = '';
  manager = '';
  priority = 'NORMAL';
  deadline = '';
  budget = '';
  contextText = '';
  query = '';
  errors: Record<string, string> = {};
  readonly steps = ['Goal & context', 'Execution', 'Review'];
  constructor() {
    effect(() => {
      const identity = this.auth.user()?.id;
      if (identity !== this.identity) {
        this.identity = identity;
        this.clear();
        this.context.set(null);
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => this.clear());
  }
  ngOnInit(): void {
    this.route.paramMap
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe(params => {
        this.clear();
        this.id = params.get('id') || '';
        this.teamId = params.get('teamId') || '';
        this.missionId = params.get('missionId') || '';
        void this.load();
      });
  }
  private clear(): void {
    this.finished = false;
    this.version++;
    this.candidateVersion++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.candidateRequest?.unsubscribe();
    this.title =
      this.goal =
      this.requiredOutput =
      this.manager =
      this.deadline =
      this.budget =
      this.contextText =
      this.query =
        '';
    this.priority = 'NORMAL';
    this.teamName.set('');
    this.step = 1;
    this.errors = {};
    this.attempt = null;
    this.known.set({});
    this.candidates.set(null);
    this.candidateLoading.set(false);
    this.candidateError.set('');
    this.saved.set(null);
    this.busy.set(false);
    this.frozen.set(false);
    this.keyConflict.set(false);
    this.closeMenu();
    this.context.set(null);
  }
  async load(): Promise<void> {
    const version = ++this.version;
    const account = this.auth.user()?.id;
    const active = () =>
      version === this.version &&
      !this.destroy.destroyed &&
      account === this.auth.user()?.id;
    this.loading.set(true);
    this.context.set(null);
    this.error.set('');
    this.denied.set(false);
    try {
      const context = await this.auth.getWorkspace(this.id);
      if (!active()) return;
      if (
        context.id !== this.id ||
        !['ADMIN', 'MEMBER'].includes(context.role) ||
        !uuid(this.teamId) ||
        !uuid(this.id)
      ) {
        this.revoke();
        return;
      }
      this.context.set(context);
      if (this.missionId) {
        this.loadDetail();
        return;
      }
      if (context.role !== 'ADMIN') {
        this.revoke();
        return;
      }
      this.requests.add(
        this.teamApi.detail(this.id, this.teamId).subscribe({
          next: r => {
            if (!active()) return;
            const t = r?.data;
            if (
              !t ||
              t.id !== this.teamId ||
              t.organizationId !== this.id ||
              typeof t.name !== 'string' ||
              !t.name.trim() ||
              !['DRAFT', 'ACTIVE'].includes(t.status)
            ) {
              this.revoke();
              return;
            }
            this.teamName.set(t.name);
            this.loading.set(false);
            this.loadCandidates();
          },
          error: e => {
            if (active()) {
              this.loading.set(false);
              this.handleError(e);
            }
          },
        }),
      );
    } catch (error) {
      if (!active()) return;
      this.loading.set(false);
      this.handleError(error);
    }
  }
  private revoke(): void {
    this.clear();
    this.context.set(null);
    this.denied.set(true);
    this.loading.set(false);
    this.error.set('');
  }
  private handleError(error: unknown): void {
    const status = error instanceof HttpErrorResponse ? error.status : 0;
    if (
      status === 403 ||
      status === 404 ||
      (error instanceof AuthFailure && error.kind === 'not-found')
    )
      this.revoke();
    else if (
      status === 401 ||
      (error instanceof AuthFailure && error.kind === 'unauthorized')
    ) {
      this.revoke();
      void this.router.navigateByUrl('/login');
    } else
      this.error.set(
        'Unable to load current data. Check your connection and try again.',
      );
  }
  loadDetail(): void {
    if (!this.context() || !uuid(this.missionId)) {
      this.revoke();
      return;
    }
    const version = ++this.version;
    this.loading.set(true);
    this.saved.set(null);
    this.error.set('');
    this.requests.add(
      this.api.detail(this.id, this.teamId, this.missionId).subscribe({
        next: response => {
          const data = response?.data;
          if (version !== this.version) return;
          this.loading.set(false);
          if (
            !validMission(data, this.id, this.teamId) ||
            data.id !== this.missionId
          ) {
            this.error.set('Invalid mission response. Try loading again.');
            return;
          }
          this.teamName.set(data.teamName);
          this.saved.set(data);
        },
        error: error => {
          if (version === this.version) {
            this.loading.set(false);
            this.handleError(error);
          }
        },
      }),
    );
  }
  loadCandidates(page = 0): void {
    if (
      this.denied() ||
      this.missionId ||
      this.context()?.role !== 'ADMIN' ||
      this.frozen() ||
      !Number.isSafeInteger(page) ||
      page < 0 ||
      page > 100000 ||
      this.query.length > 100
    )
      return;
    const version = ++this.candidateVersion;
    const scope = this.version;
    this.candidateRequest?.unsubscribe();
    this.candidateLoading.set(true);
    this.candidateError.set('');
    this.candidates.set(null);
    this.candidateRequest = this.api
      .managers(this.id, this.teamId, page, this.query.slice(0, 100))
      .subscribe({
        next: response => {
          const data = response?.data;
          if (version !== this.candidateVersion || scope !== this.version)
            return;
          this.candidateLoading.set(false);
          if (
            !validPage(
              data,
              page,
              item =>
                !!item &&
                uuid(item.id) &&
                typeof item.name === 'string' &&
                !!item.name.trim() &&
                item.level === 'MANAGER' &&
                ['DRAFT', 'ACTIVE'].includes(item.status),
            )
          ) {
            this.candidateError.set('Unable to load managers.');
            return;
          }
          const items = data.items.filter(
            item => uuid(item.id) && ['DRAFT', 'ACTIVE'].includes(item.status),
          );
          this.candidates.set({...data, items});
          this.known.update(known => ({
            ...known,
            ...Object.fromEntries(items.map(item => [item.id, item])),
          }));
        },
        error: error => {
          if (version !== this.candidateVersion || scope !== this.version)
            return;
          this.candidateLoading.set(false);
          if (
            error instanceof HttpErrorResponse &&
            [401, 403, 404].includes(error.status)
          )
            this.handleError(error);
          else this.candidateError.set('Unable to load managers. Try again.');
        },
      });
  }
  managers(): Candidate[] {
    return Object.values(this.known()).filter(
      a =>
        a.id === this.manager ||
        this.candidates()?.items.some(c => c.id === a.id),
    );
  }
  managerLabel(): string {
    const a = this.known()[this.manager];
    return a
      ? `${a.name} · ${a.status === 'DRAFT' ? 'DRAFT — configuration only' : 'ACTIVE'}`
      : 'Set up later';
  }
  validate(all = false): boolean {
    this.errors = {};
    if (all || this.step === 1) {
      for (const [key, value, max] of [
        ['title', this.title, 255],
        ['goal', this.goal, 4000],
        ['requiredOutput', this.requiredOutput, 4000],
      ] as const) {
        if (!value.trim() || value.trim().length > max)
          this.errors[key] = `Required, maximum ${max} characters.`;
      }
    }
    if (all || this.step === 2) {
      if (this.manager && !this.known()[this.manager])
        this.errors['manager'] = 'Select an eligible manager.';
      if (!['LOW', 'NORMAL', 'HIGH', 'URGENT'].includes(this.priority))
        this.errors['priority'] = 'Select a priority.';
      if (this.deadline && !validDate(this.deadline))
        this.errors['deadline'] = 'Enter a valid date (YYYY-MM-DD).';
      if (
        this.budget !== '' &&
        (!/^\d+(\.\d{1,2})?$/.test(String(this.budget)) ||
          +this.budget > 1000000)
      )
        this.errors['budget'] = 'Enter 0–1000000 USD with at most 2 decimals.';
      if (this.contextText.length > 8000)
        this.errors['context'] = 'Maximum 8000 characters.';
    }
    if (Object.keys(this.errors).length) {
      if (all)
        this.step = ['title', 'goal', 'requiredOutput'].some(
          k => this.errors[k],
        )
          ? 1
          : 2;
      setTimeout(() => {
        if (!this.destroy.destroyed)
          this.element.nativeElement
            .querySelector<HTMLElement>('[aria-invalid="true"]')
            ?.focus();
      });
    }
    return !Object.keys(this.errors).length;
  }
  next(): void {
    if (!this.frozen() && this.step < 3 && this.validate()) {
      this.step++;
      this.error.set('');
    }
  }
  back(): void {
    if (!this.frozen() && !this.busy() && this.step > 1) {
      this.step--;
      this.errors = {};
      this.error.set('');
    }
  }
  private hasUnsavedChanges(): boolean {
    return (
      !this.finished &&
      !this.missionId &&
      !this.denied() &&
      !!(
        this.title ||
        this.goal ||
        this.requiredOutput ||
        this.manager ||
        this.priority !== 'NORMAL' ||
        this.deadline ||
        this.budget ||
        this.contextText ||
        this.attempt
      )
    );
  }
  @HostListener('document:keydown.escape')
  closeMenu(): void {
    const wasOpen = this.menuOpen();
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#mission-menu')
      ?.close();
    if (wasOpen)
      this.element.nativeElement
        .querySelector<HTMLElement>('[aria-controls="mission-menu"]')
        ?.focus();
  }
  toggleMenu(): void {
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#mission-menu')
      ?.showModal();
  }
  canLeave(): boolean {
    return (
      !this.hasUnsavedChanges() ||
      window.confirm('Leave and discard unsaved mission changes?')
    );
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.hasUnsavedChanges()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
  cancel(): void {
    if (!this.busy() && window.confirm('Discard unsaved mission changes?')) {
      this.clear();
      void this.router.navigate([
        '/workspace',
        this.id,
        'teams',
        this.teamId,
        'workspace',
      ]);
    }
  }
  create(): void {
    if (
      this.step !== 3 ||
      this.busy() ||
      this.keyConflict() ||
      this.denied() ||
      this.context()?.role !== 'ADMIN'
    )
      return;
    if (!this.attempt) {
      if (!this.validate(true)) return;
      const body: MissionBody = {
        title: this.title.trim(),
        goal: this.goal.trim(),
        requiredOutput: this.requiredOutput.trim(),
        managerAgentId: this.manager || null,
        priority: this.priority,
        deadline: this.deadline || null,
        budgetLimit: this.budget === '' ? null : +this.budget,
        approvalMode: 'HUMAN_REQUIRED',
        context: this.contextText || null,
      };
      Object.freeze(body);
      this.attempt = {key: crypto.randomUUID(), body};
    }
    this.frozen.set(true);
    this.busy.set(true);
    this.error.set('');
    const version = this.version;
    this.requests.add(
      this.api
        .create(this.id, this.teamId, this.attempt.body, this.attempt.key)
        .subscribe({
          next: response => {
            const data = response?.data;
            if (version !== this.version) return;
            this.busy.set(false);
            if (
              !validMission(data, this.id, this.teamId, true) ||
              !this.matchesAttempt(data)
            ) {
              this.error.set(
                'Save outcome is unconfirmed. Fields are locked. Retry Create mission to send the same request safely.',
              );
              return;
            }
            this.finished = true;
            void this.router.navigate([
              '/workspace',
              this.id,
              'teams',
              this.teamId,
              'missions',
              data.id,
            ]);
          },
          error: error => {
            if (version !== this.version) return;
            this.busy.set(false);
            if (error instanceof HttpErrorResponse) {
              const code = error.error?.error?.code;
              if ([401, 403, 404].includes(error.status)) {
                this.handleError(error);
                return;
              }
              if (error.status === 409 && code === 'IDEMPOTENCY_CONFLICT') {
                this.keyConflict.set(true);
                this.error.set(
                  'Idempotency conflict. This request is locked. Contact an administrator.',
                );
                return;
              }
              if (
                error.status === 400 ||
                (error.status === 409 && code === 'MISSION_SETUP_CONFLICT')
              ) {
                this.attempt = null;
                this.frozen.set(false);
                this.step = error.status === 400 ? 1 : 2;
                this.manager = '';
                this.known.set({});
                this.error.set(
                  code === 'MISSION_SETUP_CONFLICT'
                    ? 'Team or manager setup changed. Check the team and select an eligible manager again.'
                    : 'Invalid configuration. Check your entries and try again.',
                );
                this.loadCandidates();
                return;
              }
            }
            this.error.set(
              'Save outcome is unconfirmed. Fields are locked. Retry Create mission to send the same request safely.',
            );
          },
        }),
    );
  }
  private matchesAttempt(data: MissionDetail): boolean {
    const b = this.attempt?.body;
    return (
      !!b &&
      Object.keys(b).every(k => {
        const key = k as keyof MissionBody;
        return key === 'context'
          ? (data.context || null) === b.context
          : data[key] === b[key];
      })
    );
  }
}

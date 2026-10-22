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
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {AuthService} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
import {MissionWorkspaceService} from './mission-workspace.service';
import {validDetail} from './task-detail.service';
import {
  Candidate,
  Candidates,
  CreationContext,
  NewTaskCreateService,
  TaskBody,
  validCandidates,
  validContext,
} from './new-task-create.service';
@Component({
  selector: 'app-new-task-create',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './new-task-create.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../teams/team-workspace.component.css',
    './mission-workspace.component.css',
    './mission-page.component.css',
    './new-task-create.component.css',
  ],
})
export class NewTaskCreateComponent {
  readonly auth = inject(AuthService);
  private api = inject(NewTaskCreateService);
  private authorization = inject(MissionWorkspaceService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private destroy = inject(DestroyRef);
  private element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private lookup = new Subscription();
  private epoch = 0;
  private lookupEpoch = 0;
  private account = this.auth.user()?.id;
  private opener: HTMLElement | null = null;
  id = '';
  teamId = '';
  missionId = '';
  title = '';
  description = '';
  acceptanceCriteria = '';
  priority = 'NORMAL';
  owner: Candidate | null = null;
  dependencies: Candidate[] = [];
  readonly context = signal<CreationContext | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly picker = signal<'owner' | 'dependencies' | null>(null);
  readonly candidates = signal<Candidates | null>(null);
  readonly pickerLoading = signal(false);
  readonly pickerError = signal('');
  readonly menuOpen = signal(false);
  query = '';
  page = 0;
  private pending: {body: TaskBody; key: string} | null = null;
  private saved = false;
  get frozen() {
    return this.pending !== null;
  }
  get dirty() {
    return !!(
      this.title ||
      this.description ||
      this.acceptanceCriteria ||
      this.owner ||
      this.dependencies.length ||
      this.priority !== 'NORMAL' ||
      this.pending
    );
  }
  get allowed() {
    const c = this.context();
    return !!c && c.canCreate && !c.intakePaused && !this.denied();
  }
  constructor() {
    effect(() => {
      const a = this.auth.user()?.id;
      if (a !== this.account) {
        this.account = a;
        this.clear(true);
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => this.clear(true));
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(p => {
      this.clear(true);
      this.id = p.get('id') || '';
      this.teamId = p.get('teamId') || '';
      this.missionId = p.get('missionId') || '';
      this.load();
    });
  }
  private clear(privateData = false) {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.closePicker();
    this.closeMenu();
    this.context.set(null);
    this.loading.set(false);
    this.busy.set(false);
    if (privateData) {
      this.title = '';
      this.description = '';
      this.acceptanceCriteria = '';
      this.priority = 'NORMAL';
      this.owner = null;
      this.dependencies = [];
      this.pending = null;
      this.saved = false;
      this.error.set('');
      this.query = '';
    }
  }
  load() {
    this.clear();
    this.error.set('');
    this.denied.set(false);
    if (![this.id, this.teamId, this.missionId].every(uuid)) {
      this.denied.set(true);
      return;
    }
    const epoch = this.epoch;
    const account = this.auth.user()?.id;
    const active = () =>
      epoch === this.epoch &&
      account === this.auth.user()?.id &&
      !this.destroy.destroyed;
    this.loading.set(true);
    this.requests.add(
      this.authorization.authorize(this.id).subscribe({
        next: r => {
          if (!active()) return;
          if (
            !this.account ||
            r?.data?.id !== this.id ||
            r.data.role !== 'ADMIN'
          ) {
            this.clear(true);
            this.denied.set(true);
            return;
          }
          this.requests.add(
            this.api.context(this.id, this.teamId, this.missionId).subscribe({
              next: c => {
                if (!active()) return;
                if (!validContext(c, this.id, this.teamId, this.missionId)) {
                  this.fail();
                  return;
                }
                this.context.set(c);
                this.loading.set(false);
              },
              error: e => {
                if (active()) this.fail(e);
              },
            }),
          );
        },
        error: e => {
          if (active()) this.fail(e);
        },
      }),
    );
  }
  private fail(e?: unknown) {
    if (e instanceof HttpErrorResponse && [401, 403, 404].includes(e.status)) {
      this.clear(true);
      this.denied.set(true);
    } else {
      this.loading.set(false);
      this.error.set('Unable to load current creation context. Try again.');
    }
  }
  canLeave() {
    return (
      this.saved ||
      !this.dirty ||
      window.confirm(
        this.pending
          ? 'The task may already have been saved. Leaving loses its safe retry receipt; submitting it again could create a duplicate. Leave anyway?'
          : 'Discard unsaved task changes?',
      )
    );
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(
    e: BeforeUnloadEvent,
  ) {
    if (!this.saved && this.dirty) e.preventDefault();
  }
  cancel() {
    void this.router.navigate([
      '/workspace',
      this.id,
      'teams',
      this.teamId,
      'missions',
      this.missionId,
      'workspace',
    ]);
  }
  openPicker(kind: 'owner' | 'dependencies') {
    if (this.frozen || this.busy() || !this.allowed) return;
    this.opener = document.activeElement as HTMLElement;
    this.picker.set(kind);
    this.query = '';
    this.page = 0;
    this.dialog('task-picker')?.showModal();
    this.search(0);
  }
  closePicker() {
    this.lookupEpoch++;
    this.lookup.unsubscribe();
    this.lookup = new Subscription();
    this.dialog('task-picker')?.close();
    this.picker.set(null);
    this.candidates.set(null);
    this.pickerError.set('');
    this.pickerLoading.set(false);
    this.opener?.focus();
    this.opener = null;
  }
  search(page = 0) {
    this.lookup.unsubscribe();
    this.lookup = new Subscription();
    const kind = this.picker();
    if (!kind) return;
    const lookupEpoch = ++this.lookupEpoch;
    const epoch = this.epoch;
    const account = this.auth.user()?.id;
    const active = () =>
      lookupEpoch === this.lookupEpoch &&
      epoch === this.epoch &&
      account === this.auth.user()?.id &&
      this.picker() === kind;
    this.page = page;
    this.candidates.set(null);
    this.pickerError.set('');
    this.pickerLoading.set(true);
    this.lookup.add(
      this.api
        .candidates(
          this.id,
          this.teamId,
          this.missionId,
          kind === 'owner',
          page,
          this.query.trim().slice(0, 100),
        )
        .subscribe({
          next: r => {
            if (!active()) return;
            this.pickerLoading.set(false);
            if (!validCandidates(r, page, kind === 'owner')) {
              this.pickerError.set('Invalid selection response. Try again.');
              return;
            }
            this.candidates.set(r);
          },
          error: e => {
            if (!active()) return;
            this.pickerLoading.set(false);
            if (
              e instanceof HttpErrorResponse &&
              [401, 403, 404].includes(e.status)
            ) {
              this.clear(true);
              this.denied.set(true);
            } else
              this.pickerError.set('Unable to load selections. Try again.');
          },
        }),
    );
  }
  selected(id: string) {
    return this.dependencies.some(d => d.id === id);
  }
  select(c: Candidate) {
    if (this.frozen) return;
    if (this.picker() === 'owner') {
      this.owner = c;
      this.closePicker();
    } else if (this.selected(c.id))
      this.dependencies = this.dependencies.filter(d => d.id !== c.id);
    else if (this.dependencies.length < 20)
      this.dependencies = [...this.dependencies, c];
  }
  submit() {
    if (this.busy() || !this.allowed) return;
    this.error.set('');
    if (!this.pending) {
      const title = this.title.trim(),
        description = this.description.trim(),
        acceptanceCriteria = this.acceptanceCriteria.trim();
      if (
        !title ||
        title.length > 255 ||
        !description ||
        description.length > 8000 ||
        !acceptanceCriteria ||
        acceptanceCriteria.length > 4000 ||
        !['LOW', 'NORMAL', 'HIGH', 'URGENT'].includes(this.priority) ||
        this.dependencies.length > 20
      ) {
        this.error.set('Complete all required fields within their limits.');
        return;
      }
      this.pending = {
        key: crypto.randomUUID(),
        body: {
          title,
          description,
          acceptanceCriteria,
          priority: this.priority,
          ownerAgentId: this.owner?.id ?? null,
          dependencyIds: this.dependencies.map(d => d.id),
          expectedIntakeVersion: this.context()!.intakeVersion,
        },
      };
    }
    this.busy.set(true);
    this.closePicker();
    const epoch = this.epoch;
    const account = this.auth.user()?.id;
    const pending = this.pending;
    this.requests.add(
      this.api
        .create(this.id, this.teamId, this.missionId, pending.body, pending.key)
        .subscribe({
          next: r => {
            if (
              epoch !== this.epoch ||
              account !== this.auth.user()?.id ||
              this.destroy.destroyed
            )
              return;
            this.busy.set(false);
            const data = r.body?.data;
            if (
              ![200, 201].includes(r.status) ||
              !data ||
              !validDetail(
                data,
                this.id,
                this.teamId,
                this.missionId,
                data.task?.id,
              ) ||
              data.task.status !== 'DRAFT' ||
              data.task.title !== pending.body.title ||
              data.task.description !== pending.body.description ||
              data.task.acceptanceCriteria !==
                pending.body.acceptanceCriteria ||
              data.task.priority !== pending.body.priority ||
              data.task.ownerAgentId !== pending.body.ownerAgentId ||
              JSON.stringify([...data.task.dependencyIds].sort()) !==
                JSON.stringify([...pending.body.dependencyIds].sort())
            ) {
              this.error.set(
                'Creation result could not be verified. Retry the same submission.',
              );
              return;
            }
            this.saved = true;
            this.pending = null;
            void this.router.navigate([
              '/workspace',
              this.id,
              'teams',
              this.teamId,
              'missions',
              this.missionId,
              'tasks',
              data.task.id,
            ]);
          },
          error: e => {
            if (
              epoch !== this.epoch ||
              account !== this.auth.user()?.id ||
              this.destroy.destroyed
            )
              return;
            this.busy.set(false);
            if (
              e instanceof HttpErrorResponse &&
              [401, 403, 404].includes(e.status)
            ) {
              this.clear(true);
              this.denied.set(true);
            } else if (
              e instanceof HttpErrorResponse &&
              [400, 409].includes(e.status)
            ) {
              this.pending = null;
              if (e.status === 409) {
                this.load();
                this.error.set(
                  'Intake changed. Review the refreshed context and submit again.',
                );
              } else
                this.error.set(
                  'Task was rejected. Check the fields and selections, then try again.',
                );
            } else
              this.error.set(
                'Creation outcome is uncertain. Retry the same submission to safely check the result.',
              );
          },
        }),
    );
  }
  private dialog(id: string) {
    return this.element.nativeElement.querySelector<HTMLDialogElement>(
      `#${id}`,
    );
  }
  toggleMenu() {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.dialog('task-create-menu')?.showModal();
  }
  closeMenu() {
    const open = this.menuOpen();
    this.menuOpen.set(false);
    this.dialog('task-create-menu')?.close();
    if (open)
      this.element.nativeElement
        .querySelector<HTMLElement>('[aria-controls="task-create-menu"]')
        ?.focus();
  }
}

import {HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  effect,
  inject,
  signal,
} from '@angular/core';
import {NgTemplateOutlet} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {Subject} from 'rxjs';
import {AuthFailure, AuthService} from '../../core/auth/auth.service';
import {TaskDependenciesService} from './task-dependencies.service';
import {TaskDependenciesNavigationState} from './task-dependencies-navigation.guard';
import {
  Body,
  Candidate,
  CandidatePage,
  Context,
  Locator,
  Receipt,
  Scope,
  Task,
  blockerLabels,
  errorLabels,
  candidate,
  exact,
  sameIds,
  sameScope,
  strip,
  text,
  uuid,
  validBody,
  validContext,
  validPage,
  validReceipt,
} from './task-dependencies-validation';

type DraftTarget = Task & {fingerprint?: string};

type Phase =
  | 'loading'
  | 'edit'
  | 'review'
  | 'saving'
  | 'saved'
  | 'unknown'
  | 'blocked'
  | 'conflict'
  | 'error'
  | 'denied';
@Component({
  selector: 'app-task-dependencies',
  standalone: true,
  imports: [FormsModule, RouterLink, NgTemplateOutlet],
  templateUrl: './task-dependencies.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../agents/agent-studio-page.component.css',
    './task-dependencies.component.css',
  ],
})
export class TaskDependenciesComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(TaskDependenciesService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly navigation = inject(TaskDependenciesNavigationState);
  private readonly cancel = new Subject<void>();
  private epoch = 0;
  private actor = this.auth.user()?.id;
  private owner = this.actor;
  private source: Context | null = null;
  private locator: Locator | null = null;
  private frozen: {body: Body; before: Task[]} | null = null;
  private searchEpoch = 0;
  private revalidating = false;
  private discardResolve: ((ok: boolean) => void) | null = null;
  private allowedNavigation = 0;
  id = '';
  teamId = '';
  missionId = '';
  taskId = '';
  readonly phase = signal<Phase>('loading');
  readonly context = signal<Context | null>(null);
  readonly receipt = signal<Receipt | null>(null);
  readonly selection = signal<DraftTarget[]>([]);
  readonly candidates = signal<CandidatePage | null>(null);
  readonly busy = signal(false);
  readonly verified = signal(false);
  readonly error = signal('');
  readonly chooserLoading = signal(false);
  readonly chooserError = signal('');
  readonly menuOpen = signal(false);
  query = '';
  page = 0;
  acknowledged = false;
  readonly blockerLabels = blockerLabels;
  constructor() {
    this.navigation.active = this;
    effect(() => {
      const a = this.auth.user()?.id;
      if (a !== this.actor) {
        this.actor = a;
        if (a && a !== this.owner) this.locator = null;
        this.loseAuthority();
      }
    });
    this.destroy.onDestroy(() => {
      this.clearPrivate();
      this.locator = null;
      this.cancel.complete();
      this.finishDiscard(false);
      if (this.navigation.active === this) this.navigation.active = null;
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(p => {
      if (
        (['id', 'teamId', 'missionId', 'taskId'] as const).every(
          k => this[k] === p.get(k),
        )
      )
        return;
      this.clearPrivate();
      this.locator = null;
      for (const k of ['id', 'teamId', 'missionId', 'taskId'] as const)
        this[k] = p.get(k) ?? '';
      this.actor = this.auth.user()?.id;
      this.owner = this.actor;
      // A reused route owns a fresh scope; an old pending save must not block its load.
      this.phase.set('loading');
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
  detailLink(id = this.taskId) {
    return [
      '/workspace',
      this.id,
      'teams',
      this.teamId,
      'missions',
      this.missionId,
      'tasks',
      id,
    ];
  }
  displayTitle(title: string) {
    return strip(title) ? title : 'Không có tiêu đề';
  }
  title(t: Task) {
    return strip(t.title) ? t.title : 'Không có tiêu đề';
  }
  private active(e: number) {
    return (
      e === this.epoch &&
      !this.destroy.destroyed &&
      this.actor === this.auth.user()?.id
    );
  }
  private clearPrivate() {
    this.epoch++;
    this.cancel.next();
    this.context.set(null);
    this.receipt.set(null);
    this.selection.set([]);
    this.candidates.set(null);
    this.source = null;
    this.frozen = null;
    this.query = '';
    this.page = 0;
    this.searchEpoch++;
    this.chooserLoading.set(false);
    this.chooserError.set('');
    this.acknowledged = false;
    this.verified.set(false);
    this.busy.set(false);
    this.error.set('');
    this.closeChooser();
    this.closeMenu();
  }
  private loseAuthority() {
    if (this.locator && this.actor && this.locator.actor !== this.actor)
      this.locator = null;
    this.clearPrivate();
    this.phase.set(this.locator ? 'unknown' : 'denied');
    this.error.set(errorLabels['FORBIDDEN']);
  }
  private authorityUnavailable() {
    // Fence callers too: none may restore a captured private view after failure.
    this.epoch++;
    this.cancel.next();
    this.hide();
    this.busy.set(false);
    this.phase.set(this.locator ? 'unknown' : 'error');
    this.error.set(errorLabels['TASK_DEPENDENCIES_UNAVAILABLE']);
  }
  private async authorize(e: number) {
    try {
      const a = this.actor;
      const s: unknown = await this.api.session(this.cancel);
      if (!this.active(e)) return false;
      if (
        !exact(s, 'id email displayName') ||
        !uuid(s['id']) ||
        !text(s['email'], 320) ||
        !s['email'] ||
        !text(s['displayName'], 255)
      ) {
        this.authorityUnavailable();
        return false;
      }
      if (!a || s['id'] !== a) {
        if (s['id'] !== this.owner) this.locator = null;
        this.loseAuthority();
        return false;
      }
      const w = await this.api.workspace(this.id, this.cancel);
      if (!this.active(e)) return false;
      if (
        !exact(w, 'id name role') ||
        w['id'] !== this.id ||
        !text(w['name'], 255) ||
        !text(w['role'], 50) ||
        !strip(w['role'])
      ) {
        this.authorityUnavailable();
        return false;
      }
      if (w['role'] !== 'ADMIN') {
        this.loseAuthority();
        return false;
      }
      this.verified.set(true);
      return true;
    } catch (x) {
      if (this.active(e)) {
        if (
          (x instanceof HttpErrorResponse &&
            [401, 403, 404].includes(x.status)) ||
          (x instanceof AuthFailure &&
            ['unauthorized', 'not-found'].includes(x.kind))
        )
          this.loseAuthority();
        else this.authorityUnavailable();
      }
      return false;
    }
  }
  private message(x: unknown) {
    if (!(x instanceof HttpErrorResponse))
      return errorLabels['TASK_DEPENDENCIES_UNAVAILABLE'];
    let payload = x.error;
    if (payload instanceof ArrayBuffer) {
      try {
        payload = JSON.parse(
          new TextDecoder('utf-8', {fatal: true}).decode(payload),
        );
      } catch {
        payload = null;
      }
    }
    const code = payload?.error?.code;
    return (
      (typeof code === 'string' && Object.hasOwn(errorLabels, code)
        ? errorLabels[code]
        : undefined) ?? errorLabels['TASK_DEPENDENCIES_UNAVAILABLE']
    );
  }
  private fail(x: unknown) {
    this.acknowledged = false;
    if (x instanceof HttpErrorResponse && [401, 403].includes(x.status)) {
      this.loseAuthority();
      return;
    }
    this.error.set(this.message(x));
    this.phase.set('error');
  }
  dirty() {
    return (
      !!this.source && !sameIds(this.source.dependencies, this.selection())
    );
  }
  editable() {
    return (
      this.phase() === 'edit' &&
      this.verified() &&
      !!this.context()?.canEdit &&
      !this.busy()
    );
  }
  private conflict() {
    this.acknowledged = false;
    this.phase.set('conflict');
    this.error.set(errorLabels['TASK_DEPENDENCIES_CONFLICT']);
  }
  async load() {
    if (this.busy() || this.phase() === 'unknown' || this.phase() === 'saving')
      return;
    if (this.dirty() && !(await this.confirmDiscard())) return;
    this.clearPrivate();
    this.locator = null;
    this.phase.set('loading');
    if (
      !Object.values(this.scope()).every(uuid) ||
      this.route.snapshot.queryParamMap.keys.length
    ) {
      this.phase.set('error');
      this.error.set(errorLabels['BAD_REQUEST']);
      return;
    }
    const e = this.epoch;
    try {
      if (!(await this.authorize(e))) return;
      const c = await this.api.context(this.scope(), this.cancel);
      if (!this.active(e)) return;
      if (!validContext(c, this.scope())) throw Error();
      this.context.set(c);
      this.source = c;
      if (!c.canEdit) {
        this.phase.set('blocked');
        return;
      }
      this.selection.set(c.dependencies);
      const targets = await this.hydrate(
        c.dependencies.map(t => t.id),
        e,
      );
      if (!this.active(e)) return;
      const fresh = await this.api.context(this.scope(), this.cancel);
      if (!this.active(e)) return;
      if (
        !validContext(fresh, this.scope()) ||
        fresh.revision !== c.revision ||
        fresh.fingerprint !== c.fingerprint
      ) {
        this.selection.set(targets);
        this.conflict();
        return;
      }
      this.selection.set(targets);
      this.phase.set('edit');
    } catch (x) {
      if (this.active(e)) {
        if (x instanceof HttpErrorResponse && [401, 403].includes(x.status))
          this.loseAuthority();
        else if (this.source?.canEdit) this.conflict();
        else this.fail(x);
      }
    }
  }
  private async hydrate(ids: string[], e: number) {
    const result: Candidate[] = new Array(ids.length);
    let next = 0;
    await Promise.all(
      Array.from({length: Math.min(4, ids.length)}, async () => {
        while (this.active(e)) {
          const i = next++;
          if (i >= ids.length) return;
          const t = await this.api.candidate(this.scope(), ids[i], this.cancel);
          if (!this.active(e)) return;
          if (!candidate(t) || t.id !== ids[i]) throw Error();
          result[i] = t;
        }
      }),
    );
    return result;
  }
  body(): Body | null {
    if (!this.source) return null;
    const b = {
      expectedRevision: this.source.revision,
      expectedFingerprint: this.source.fingerprint,
      dependencies: this.selection().map(t => ({
        taskId: t.id,
        fingerprint: t.fingerprint,
      })),
    };
    return validBody(b, this.taskId) ? b : null;
  }
  choose(t: Candidate) {
    if (
      !this.editable() ||
      this.selection().length >= 20 ||
      this.isSelected(t.id) ||
      !candidate(t) ||
      t.id === this.taskId
    )
      return;
    this.selection.update(a =>
      [...a, t].sort((x, y) => (x.id < y.id ? -1 : 1)),
    );
    this.acknowledged = false;
  }
  isAdded(id: string) {
    return !this.source?.dependencies.some(t => t.id === id);
  }
  isSelected(id: string) {
    return this.selection().some(t => t.id === id);
  }
  remove(id: string) {
    if (!this.editable()) return;
    this.selection.update(a => a.filter(t => t.id !== id));
    this.acknowledged = false;
  }
  clear() {
    if (!this.editable()) return;
    this.selection.set([]);
    this.acknowledged = false;
  }
  async search(page = 0) {
    if (!this.editable()) return;
    const e = this.epoch,
      n = ++this.searchEpoch,
      q = strip(this.query);
    this.candidates.set(null);
    this.chooserError.set('');
    if (!text(q, 100)) {
      this.chooserError.set(errorLabels['BAD_REQUEST']);
      return;
    }
    this.chooserLoading.set(true);
    try {
      const p = await this.api.candidates(this.scope(), page, q, this.cancel);
      if (!this.active(e) || n !== this.searchEpoch) return;
      if (!validPage(p, this.scope(), page, q)) throw Error();
      this.page = page;
      this.candidates.set(p);
    } catch (x) {
      if (this.active(e) && n === this.searchEpoch) {
        if (x instanceof HttpErrorResponse && [401, 403].includes(x.status))
          this.loseAuthority();
        else this.chooserError.set(this.message(x));
      }
    } finally {
      if (this.active(e) && n === this.searchEpoch)
        this.chooserLoading.set(false);
    }
  }
  // Take private data off the rendered surface before any asynchronous authority check.
  private hide() {
    const snapshot = {
      context: this.context(),
      selection: this.selection(),
      receipt: this.receipt(),
    };
    this.context.set(null);
    this.selection.set([]);
    this.receipt.set(null);
    this.candidates.set(null);
    this.query = '';
    this.searchEpoch++;
    this.closeChooser();
    this.verified.set(false);
    this.acknowledged = false;
    return snapshot;
  }
  private async preflight(e: number, c: Context, targets: DraftTarget[]) {
    if (!(await this.authorize(e))) return false;
    const fresh = await this.api.context(this.scope(), this.cancel);
    if (!this.active(e)) return false;
    if (
      !validContext(fresh, this.scope()) ||
      !fresh.canEdit ||
      fresh.revision !== c.revision ||
      fresh.fingerprint !== c.fingerprint
    ) {
      this.conflict();
      return false;
    }
    const checked = await this.hydrate(
      targets.map(t => t.id),
      e,
    );
    if (!this.active(e)) return false;
    if (checked.some((t, i) => t.fingerprint !== targets[i].fingerprint)) {
      this.conflict();
      return false;
    }
    return true;
  }
  async review() {
    if (!this.editable() || !this.dirty() || !this.body()) return;
    const s = this.hide(),
      e = this.epoch,
      c = this.source!;
    this.busy.set(true);
    try {
      const ok = await this.preflight(e, c, s.selection);
      if (!this.active(e)) return;
      this.context.set(s.context);
      this.selection.set(s.selection);
      if (ok) {
        this.phase.set('review');
        this.focusHeading();
      }
    } catch (x) {
      if (this.active(e)) {
        this.context.set(s.context);
        this.selection.set(s.selection);
        if (x instanceof HttpErrorResponse && [401, 403].includes(x.status))
          this.loseAuthority();
        else this.conflict();
      }
    } finally {
      if (this.active(e)) this.busy.set(false);
    }
  }
  back() {
    if (this.busy()) return;
    this.phase.set('edit');
    this.acknowledged = false;
    this.focusHeading();
  }
  async save() {
    if (
      this.phase() !== 'review' ||
      this.busy() ||
      !this.acknowledged ||
      !this.verified()
    )
      return;
    const body = this.body();
    if (!body || !this.source) return;
    const s = this.hide(),
      e = this.epoch,
      c = this.source;
    this.busy.set(true);
    try {
      const h = await this.api.csrf(this.cancel);
      if (!this.active(e)) return;
      if (!(await this.preflight(e, c, s.selection))) {
        if (this.active(e)) {
          this.context.set(s.context);
          this.selection.set(s.selection);
        }
        return;
      }
      this.locator = {
        ...this.scope(),
        actor: this.actor!,
        key: crypto.randomUUID(),
      };
      this.owner = this.actor;
      const copy = structuredClone(body);
      copy.dependencies.forEach(Object.freeze);
      Object.freeze(copy.dependencies);
      this.frozen = {
        body: Object.freeze(copy),
        before: structuredClone(c.dependencies),
      };
      this.phase.set('saving');
      await this.postFrozen(e, h);
      if (this.active(e) && this.phase() === 'conflict') {
        this.context.set(s.context);
        this.selection.set(s.selection);
      }
    } catch (x) {
      if (this.active(e)) {
        this.context.set(s.context);
        this.selection.set(s.selection);
        this.fail(x);
      }
    } finally {
      if (this.active(e)) this.busy.set(false);
    }
  }
  private async postFrozen(e: number, h: HttpHeaders) {
    const p = this.locator!,
      f = this.frozen!;
    try {
      const r = await this.api.save(p, f.body, p.key, h, this.cancel);
      if (!this.active(e)) return;
      if (!validReceipt(r, p, f.body, f.before)) throw Error();
      this.accept(r);
    } catch (x) {
      if (!this.active(e)) return;
      this.phase.set('unknown');
      this.error.set(
        'Chưa xác định kết quả lưu. Giữ nguyên yêu cầu để kiểm tra biên nhận.',
      );
      if (x instanceof HttpErrorResponse) {
        if ([401, 403].includes(x.status)) {
          this.loseAuthority();
          return;
        }
        if ([400, 409].includes(x.status)) {
          this.frozen = null;
          this.locator = null;
          this.phase.set('conflict');
          this.error.set(this.message(x));
        }
      }
    }
  }
  private accept(r: Receipt) {
    this.context.set(null);
    this.selection.set([]);
    this.source = null;
    this.frozen = null;
    this.receipt.set(r);
    this.acknowledged = false;
    this.error.set('');
    this.phase.set('saved');
    this.focusHeading();
  }
  canReplay() {
    return !!this.frozen && this.locator?.actor === this.actor;
  }
  async recover(replay = false) {
    const p = this.locator;
    if (
      !p ||
      this.busy() ||
      this.phase() !== 'unknown' ||
      (replay && !this.canReplay())
    )
      return;
    this.hide();
    const e = this.epoch;
    this.busy.set(true);
    try {
      if (!(await this.authorize(e))) return;
      if (p.actor !== this.actor || !sameScope(p, this.scope())) {
        this.locator = null;
        this.loseAuthority();
        return;
      }
      if (replay) {
        const h = await this.api.csrf(this.cancel);
        if (this.active(e) && this.frozen) await this.postFrozen(e, h);
        return;
      }
      const r = await this.api.receipt(p, p.key, this.cancel);
      if (!this.active(e)) return;
      if (
        !exact(r, 'receipt') ||
        !validReceipt(r['receipt'], p, this.frozen?.body, this.frozen?.before)
      ) {
        this.error.set(
          'Chưa xác định kết quả lưu. Giữ nguyên yêu cầu để kiểm tra biên nhận.',
        );
        return;
      }
      this.accept(r['receipt']);
    } catch (x) {
      if (this.active(e)) {
        if (x instanceof HttpErrorResponse && [401, 403].includes(x.status))
          this.loseAuthority();
        else
          this.error.set(
            'Chưa xác định kết quả lưu. Giữ nguyên yêu cầu để kiểm tra biên nhận.',
          );
      }
    } finally {
      if (this.active(e)) this.busy.set(false);
    }
  }
  @HostListener('window:focus') onFocus() {
    void this.revalidate();
  }
  @HostListener('document:visibilitychange') onVisibility() {
    if (document.visibilityState === 'visible') void this.revalidate();
  }
  async revalidate() {
    if (this.destroy.destroyed || this.discardResolve || this.revalidating)
      return;
    this.revalidating = true;
    const old = this.phase(),
      s = this.hide();
    const e = ++this.epoch;
    this.cancel.next();
    this.busy.set(true);
    if (old === 'saving') this.phase.set('unknown');
    try {
      if (!(await this.authorize(e))) return;
      if (['unknown', 'saving'].includes(old)) return;
      if (!this.source) {
        if (old === 'saved') this.receipt.set(s.receipt);
        return;
      }
      if (old === 'blocked') {
        const c = await this.api.context(this.scope(), this.cancel);
        if (!this.active(e)) return;
        if (!validContext(c, this.scope())) throw Error();
        this.context.set(c);
        if (c.canEdit) this.conflict();
        else this.phase.set('blocked');
        return;
      }
      const ok = await this.preflight(e, this.source, s.selection);
      if (!this.active(e)) return;
      this.context.set(s.context);
      this.selection.set(s.selection);
      if (ok) this.phase.set(old === 'review' ? 'edit' : old);
    } catch {
      if (this.active(e)) {
        this.context.set(s.context);
        this.selection.set(s.selection);
        this.conflict();
      }
    } finally {
      this.revalidating = false;
      if (this.active(e)) this.busy.set(false);
    }
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(
    e: BeforeUnloadEvent,
  ) {
    if (
      this.dirty() ||
      this.busy() ||
      ['unknown', 'saving'].includes(this.phase())
    ) {
      e.preventDefault();
      e.returnValue = '';
    }
  }
  canLeave(): boolean | Promise<boolean> {
    const n = this.router.getCurrentNavigation()?.id;
    if (n && n === this.allowedNavigation) return true;
    if (
      !this.dirty() &&
      !this.busy() &&
      !['unknown', 'saving'].includes(this.phase())
    )
      return true;
    return this.confirmDiscard().then(ok => {
      if (ok && n) this.allowedNavigation = n;
      return ok;
    });
  }
  private confirmDiscard() {
    if (this.discardResolve) return Promise.resolve(false);
    return new Promise<boolean>(resolve => {
      const d = this.dialog('dependencies-discard-dialog');
      if (!d?.isConnected) {
        resolve(false);
        return;
      }
      this.discardResolve = resolve;
      d.showModal();
    });
  }
  finishDiscard(ok: boolean) {
    const resolve = this.discardResolve;
    this.discardResolve = null;
    this.dialog('dependencies-discard-dialog')?.close();
    resolve?.(ok);
  }
  uncertain() {
    return ['unknown', 'saving'].includes(this.phase());
  }
  private dialog(hook: string) {
    return this.element.nativeElement.querySelector<HTMLDialogElement>(
      `[data-testid="${hook}"]`,
    );
  }
  openChooser() {
    if (!this.editable() || this.selection().length >= 20) return;
    const d = this.dialog('dependencies-chooser');
    if (d?.isConnected && !d.open) {
      d.showModal();
      void this.search();
    }
  }
  closeChooser() {
    const d = this.dialog('dependencies-chooser');
    if (d?.open) d.close();
    this.chooserClosed();
  }
  chooserClosed() {
    this.searchEpoch++;
    this.candidates.set(null);
    this.chooserLoading.set(false);
    this.chooserError.set('');
    this.element.nativeElement
      .querySelector<HTMLElement>('[data-testid="dependencies-add"]')
      ?.focus();
  }
  toggleMenu() {
    const d = this.dialog('dependencies-menu');
    if (d?.isConnected && !d.open) {
      d.showModal();
      this.menuOpen.set(true);
    }
  }
  closeMenu() {
    const d = this.dialog('dependencies-menu');
    if (d?.open) d.close();
    this.menuOpen.set(false);
  }
  private focusHeading() {
    queueMicrotask(() => {
      if (!this.destroy.destroyed)
        this.element.nativeElement.querySelector<HTMLElement>('h1')?.focus();
    });
  }
}

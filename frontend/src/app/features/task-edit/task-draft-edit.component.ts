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
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {Subject} from 'rxjs';
import {AuthFailure, AuthService} from '../../core/auth/auth.service';
import {TaskEditService} from './task-edit.service';
import {TaskEditNavigationState} from './task-edit-navigation.guard';
import {
  Body,
  Context,
  Locator,
  Receipt,
  Scope,
  Values,
  exact,
  fields,
  labels,
  sameScope,
  sameValues,
  strip,
  text,
  uuid,
  validBody,
  validContext,
  validReceipt,
} from './task-edit-validation';
@Component({
  selector: 'app-task-draft-edit',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './task-draft-edit.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../agents/agent-studio-page.component.css',
    './task-draft-edit.component.css',
  ],
})
export class TaskDraftEditComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(TaskEditService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly navigation = inject(TaskEditNavigationState);
  private readonly cancel = new Subject<void>();
  private epoch = 0;
  private actor = this.auth.user()?.id;
  private original = '';
  private source: {revision: number; fingerprint: string} | null = null;
  private frozen: {body: Body; before: Values} | null = null;
  private locator: Locator | null = null;
  private allowedNavigation = 0;
  id = '';
  teamId = '';
  missionId = '';
  taskId = '';
  readonly context = signal<Context | null>(null);
  readonly receipt = signal<Receipt | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly verified = signal(false);
  readonly denied = signal(false);
  readonly stale = signal(false);
  readonly error = signal('');
  readonly phase = signal<'edit' | 'review' | 'unknown' | 'saved'>('edit');
  readonly menuOpen = signal(false);
  readonly fields = fields;
  readonly labels = labels;
  draft = this.empty();
  acknowledged = false;
  constructor() {
    this.navigation.active = this;
    effect(() => {
      const a = this.auth.user()?.id;
      if (a !== this.actor) {
        this.actor = a;
        this.loseAuthority();
      }
    });
    this.destroy.onDestroy(() => {
      this.clearPrivate();
      this.locator = null;
      this.cancel.complete();
      if (this.navigation.active === this) this.navigation.active = null;
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(p => {
      if (
        !(['id', 'teamId', 'missionId', 'taskId'] as const).some(
          k => this[k] !== p.get(k),
        )
      )
        return;
      this.clearPrivate();
      this.locator = null;
      this.phase.set('edit');
      for (const k of ['id', 'teamId', 'missionId', 'taskId'] as const)
        this[k] = p.get(k) ?? '';
      this.actor = this.auth.user()?.id;
      void this.load();
    });
  }
  private empty() {
    return {title: '', description: '', acceptanceCriteria: '', priority: ''};
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
    this.source = null;
    this.frozen = null;
    this.draft = this.empty();
    this.original = '';
    this.acknowledged = false;
    this.verified.set(false);
    this.loading.set(false);
    this.busy.set(false);
    this.error.set('');
    this.stale.set(false);
  }
  private loseAuthority() {
    this.clearPrivate();
    this.denied.set(true);
    this.phase.set(this.locator ? 'unknown' : 'edit');
  }
  private async authorize(e: number) {
    const actor = this.actor;
    const session = await this.auth.session();
    if (!this.active(e)) return false;
    if (!actor || session?.id !== actor) {
      this.loseAuthority();
      return false;
    }
    const w = await this.api.workspace(this.id, this.cancel);
    if (!this.active(e)) return false;
    if (
      !exact(w, 'id name role') ||
      w['id'] !== this.id ||
      typeof w['name'] !== 'string' ||
      typeof w['role'] !== 'string'
    )
      throw Error('Invalid workspace');
    if (w['role'] !== 'ADMIN') {
      this.loseAuthority();
      return false;
    }
    this.denied.set(false);
    this.verified.set(true);
    return true;
  }
  private fail(e: unknown, message: string) {
    this.acknowledged = false;
    this.verified.set(false);
    if (
      (e instanceof HttpErrorResponse && [401, 403, 404].includes(e.status)) ||
      (e instanceof AuthFailure &&
        ['unauthorized', 'not-found'].includes(e.kind))
    )
      this.loseAuthority();
    else this.error.set(message);
  }
  dirty() {
    return !!this.original && JSON.stringify(this.draft) !== this.original;
  }
  canLeave() {
    this.acknowledged = false;
    if (!this.dirty() && this.phase() !== 'unknown' && !this.busy())
      return true;
    const n = this.router.getCurrentNavigation()?.id;
    if (n && n === this.allowedNavigation) return true;
    const ok = window.confirm(
      'Rời trang sẽ bỏ bản nháp và thông tin khôi phục. Kết quả chưa xác định có thể đã được lưu; không tự động gửi lại. Tiếp tục?',
    );
    if (ok && n) this.allowedNavigation = n;
    return ok;
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(
    e: BeforeUnloadEvent,
  ) {
    if (this.dirty() || this.phase() === 'unknown' || this.busy()) {
      e.preventDefault();
      e.returnValue = '';
    }
  }
  @HostListener('window:focus') onFocus() {
    void this.revalidate();
  }
  @HostListener('document:visibilitychange') onVisibility() {
    if (document.visibilityState === 'visible') void this.revalidate();
  }
  async load() {
    if (this.busy() || this.phase() === 'unknown') return;
    if (
      this.dirty() &&
      !window.confirm('Bỏ thay đổi chưa lưu và tải lại dữ liệu?')
    )
      return;
    this.clearPrivate();
    this.phase.set('edit');
    this.locator = null;
    this.denied.set(false);
    if (
      !Object.values(this.scope()).every(uuid) ||
      this.route.snapshot.queryParamMap.keys.length
    ) {
      this.error.set('Task không khả dụng.');
      return;
    }
    const e = this.epoch;
    this.loading.set(true);
    try {
      if (!(await this.authorize(e))) return;
      const c = await this.api.context(this.scope(), this.cancel);
      if (!this.active(e)) return;
      if (!validContext(c, this.scope())) throw Error('Invalid context');
      this.context.set(c);
      this.source = {revision: c.revision, fingerprint: c.fingerprint};
      this.draft = {
        title: c.values.title,
        description: c.values.description ?? '',
        acceptanceCriteria: c.values.acceptanceCriteria ?? '',
        priority: c.values.priority ?? '',
      };
      this.original = JSON.stringify(this.draft);
    } catch (x) {
      if (this.active(e)) this.fail(x, 'Không thể tải dữ liệu.');
    } finally {
      if (this.active(e)) this.loading.set(false);
    }
  }
  async revalidate() {
    if (this.destroy.destroyed) return;
    const saved = this.phase() === 'saved';
    const wasPosting = this.busy() && !!this.locator;
    const e = ++this.epoch;
    this.cancel.next();
    this.context.set(null);
    this.receipt.set(null);
    this.verified.set(false);
    this.acknowledged = false;
    this.busy.set(false);
    if (wasPosting) this.phase.set('unknown');
    this.loading.set(true);
    try {
      if (!(await this.authorize(e))) return;
      const c = await this.api.context(this.scope(), this.cancel);
      if (!this.active(e)) return;
      if (!validContext(c, this.scope())) throw Error('Invalid context');
      this.context.set(c);
      if (!this.source && this.phase() === 'edit') {
        this.source = {revision: c.revision, fingerprint: c.fingerprint};
        this.draft = {
          title: c.values.title,
          description: c.values.description ?? '',
          acceptanceCriteria: c.values.acceptanceCriteria ?? '',
          priority: c.values.priority ?? '',
        };
        this.original = JSON.stringify(this.draft);
      }
      if (saved && this.locator) {
        const data = await this.api.receipt(
          this.locator,
          this.locator.key,
          this.cancel,
        );
        if (!this.active(e)) return;
        if (
          !exact(data, 'receipt') ||
          !validReceipt(data['receipt'], this.locator)
        ) {
          this.phase.set('unknown');
          this.error.set(
            'Chưa tìm được biên nhận hợp lệ. Kết quả vẫn chưa xác định.',
          );
          return;
        }
        this.accept(data['receipt']);
      }
      if (
        this.source &&
        (c.revision !== this.source.revision ||
          c.fingerprint !== this.source.fingerprint)
      ) {
        this.stale.set(true);
        this.error.set(
          'Dữ liệu nguồn đã thay đổi. Bản nháp được giữ; tải lại dữ liệu rồi xem lại.',
        );
        if (this.phase() === 'review') this.phase.set('edit');
      }
    } catch (x) {
      if (this.active(e))
        this.fail(x, 'Không thể xác minh quyền và dữ liệu hiện tại.');
    } finally {
      if (this.active(e)) this.loading.set(false);
    }
  }
  editable() {
    return (
      !!this.context()?.canEdit &&
      this.verified() &&
      !this.stale() &&
      !this.busy() &&
      !this.loading() &&
      this.phase() === 'edit'
    );
  }
  edit() {
    this.acknowledged = false;
    this.error.set('');
  }
  body(): Body | null {
    if (
      !this.source ||
      !text(this.draft.title, Infinity) ||
      !text(this.draft.description, Infinity, true) ||
      !text(this.draft.acceptanceCriteria, Infinity, true)
    )
      return null;
    const b = {
      expectedRevision: this.source.revision,
      expectedFingerprint: this.source.fingerprint,
      ...Object.fromEntries(fields.map(k => [k, strip(this.draft[k])])),
    } as Body;
    return validBody(b) ? b : null;
  }
  review() {
    this.acknowledged = false;
    const b = this.body(),
      c = this.context();
    if (!this.editable() || !b || !c) {
      this.error.set(
        'Điền đủ Title (1–255), Description / input (1–8000), Acceptance criteria (1–4000) và Priority hợp lệ.',
      );
      return;
    }
    if (sameValues(c.values, b)) {
      this.error.set('Không có thay đổi để xem lại.');
      return;
    }
    this.phase.set('review');
    this.error.set('');
    this.focusHeading();
  }
  back() {
    this.acknowledged = false;
    this.phase.set('edit');
    this.focusHeading();
  }
  show(v: string | null) {
    return v === null ? 'Chưa có dữ liệu' : v;
  }
  async save() {
    if (
      this.busy() ||
      this.loading() ||
      !this.verified() ||
      this.phase() !== 'review' ||
      !this.acknowledged ||
      this.stale()
    )
      return;
    const b = this.body(),
      c = this.context();
    if (!b || !c?.canEdit) return;
    const e = this.epoch;
    this.busy.set(true);
    this.error.set('');
    try {
      const h = await this.api.csrf(this.cancel);
      if (!this.active(e) || !(await this.authorize(e))) return;
      const fresh = await this.api.context(this.scope(), this.cancel);
      if (!this.active(e)) return;
      if (!validContext(fresh, this.scope())) throw Error('Invalid preflight');
      if (
        !fresh.canEdit ||
        fresh.revision !== b.expectedRevision ||
        fresh.fingerprint !== b.expectedFingerprint
      ) {
        this.stale.set(true);
        this.phase.set('edit');
        this.acknowledged = false;
        this.error.set(
          'Dữ liệu nguồn đã thay đổi. Bản nháp được giữ; tải lại dữ liệu rồi xem lại.',
        );
        return;
      }
      if (!this.acknowledged) return;
      this.locator = {
        ...this.scope(),
        actor: this.actor!,
        key: crypto.randomUUID(),
      };
      this.frozen = {
        body: Object.freeze(structuredClone(b)),
        before: Object.freeze(structuredClone(c.values)),
      };
      this.phase.set('unknown');
      this.acknowledged = false;
      await this.postFrozen(e, h);
    } catch (x) {
      if (this.active(e))
        this.fail(x, 'Không thể xác minh trước khi lưu. Vui lòng xem lại.');
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
      if (!validReceipt(r, p, f.body, f.before)) throw Error('Unknown receipt');
      this.accept(r);
    } catch (x) {
      if (!this.active(e)) return;
      if (x instanceof HttpErrorResponse && [400, 409].includes(x.status)) {
        this.frozen = null;
        this.locator = null;
        this.phase.set('edit');
        this.stale.set(true);
        this.acknowledged = false;
        this.error.set('Yêu cầu bị từ chối. Tải lại dữ liệu rồi xem lại.');
      } else {
        this.phase.set('unknown');
        this.fail(
          x,
          'Chưa xác định kết quả. Kiểm tra biên nhận trước khi gửi lại yêu cầu gốc.',
        );
      }
    }
  }
  private accept(r: Receipt) {
    this.receipt.set(r);
    this.phase.set('saved');
    this.frozen = null;
    this.original = '';
    this.error.set('');
    this.focusHeading();
  }
  canReplay() {
    return !!this.frozen && !this.denied();
  }
  async recover(replay = false) {
    const p = this.locator;
    if (!p || this.busy() || this.loading() || this.phase() !== 'unknown')
      return;
    const e = this.epoch;
    this.busy.set(true);
    this.error.set('');
    this.context.set(null);
    this.receipt.set(null);
    this.acknowledged = false;
    try {
      const h = replay ? await this.api.csrf(this.cancel) : null;
      if (!this.active(e) || !(await this.authorize(e))) return;
      if (p.actor !== this.actor || !sameScope(p, this.scope())) {
        this.loseAuthority();
        return;
      }
      if (replay) {
        if (h && this.frozen) await this.postFrozen(e, h);
        return;
      }
      const data = await this.api.receipt(p, p.key, this.cancel);
      if (!this.active(e)) return;
      if (
        !exact(data, 'receipt') ||
        !validReceipt(
          data['receipt'],
          p,
          this.frozen?.body,
          this.frozen?.before,
        )
      ) {
        this.error.set(
          'Chưa tìm được biên nhận hợp lệ. Kết quả vẫn chưa xác định.',
        );
        return;
      }
      this.accept(data['receipt']);
    } catch (x) {
      if (this.active(e))
        this.fail(
          x,
          'Không thể kiểm tra biên nhận. Kết quả vẫn chưa xác định.',
        );
    } finally {
      if (this.active(e)) this.busy.set(false);
    }
  }
  private focusHeading() {
    queueMicrotask(() =>
      this.element.nativeElement.querySelector<HTMLElement>('h1')?.focus(),
    );
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
      .querySelector<HTMLElement>('[aria-controls="task-edit-menu"]')
      ?.focus();
  }
}

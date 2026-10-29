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
import {TaskOwnerService} from './task-owner.service';
import {TaskOwnerNavigationState} from './task-owner-navigation.guard';
import {
  Body,
  Context,
  Locator,
  Receipt,
  Scope,
  Values,
  exact,
  Candidate,
  CandidatePage,
  candidate,
  validPage,
  sameScope,
  strip,
  text,
  uuid,
  validBody,
  validContext,
  validReceipt,
} from './task-owner-validation';
@Component({
  selector: 'app-task-owner',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './task-owner.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../agents/agent-studio-page.component.css',
    './task-owner.component.css',
  ],
})
export class TaskOwnerComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(TaskOwnerService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly navigation = inject(TaskOwnerNavigationState);
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
  readonly candidates = signal<CandidatePage | null>(null);
  readonly chooserLoading = signal(false);
  readonly chooserError = signal('');
  selected: Candidate | null = null;
  explicitSelection = false;
  query = '';
  page = 0;
  private searchEpoch = 0;
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
    return {ownerAgentId: null as string | null};
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
    this.query = '';
    this.page = 0;
    this.closeChooser();
    this.closeMenu();
    this.candidates.set(null);
    this.searchEpoch++;
    this.chooserLoading.set(false);
    this.chooserError.set('');
    this.selected = null;
    this.explicitSelection = false;
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
    if (this.locator?.actor !== this.actor) this.locator = null;
    this.clearPrivate();
    this.denied.set(true);
    this.phase.set(this.locator ? 'unknown' : 'edit');
  }
  private async authorize(e: number, search?: number) {
    const current = () =>
      this.active(e) && (search === undefined || search === this.searchEpoch);
    try {
      const actor = this.actor;
      const session = await this.auth.session();
      if (!current()) return false;
      if (!actor || session?.id !== actor) {
        this.locator = null;
        this.loseAuthority();
        return false;
      }
      const w = await this.api.workspace(this.id, this.cancel);
      if (!current()) return false;
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
    } catch (x) {
      if (current()) {
        this.loseAuthority();
        this.error.set(
          'Không thể xác minh quyền hiện tại. Vui lòng thử kiểm tra lại.',
        );
      }
      throw x;
    }
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
    return (
      this.explicitSelection &&
      (this.context()?.owner.state === 'UNAVAILABLE' ||
        JSON.stringify(this.draft) !== this.original)
    );
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
      this.draft = {ownerAgentId: c.owner.agent?.id ?? null};
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
        this.draft = {ownerAgentId: c.owner.agent?.id ?? null};
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
    if (!this.source || !this.explicitSelection) return null;
    const b = {
      expectedRevision: this.source.revision,
      expectedFingerprint: this.source.fingerprint,
      ownerAgentId: this.draft.ownerAgentId,
      ownerFingerprint: this.selected?.fingerprint ?? null,
    };
    return validBody(b) ? b : null;
  }
  desired(): Values {
    const a = this.selected;
    return a
      ? {
          state: 'ASSIGNED',
          agent: {id: a.id, name: a.name, level: a.level, status: a.status},
        }
      : {state: 'UNASSIGNED', agent: null};
  }
  choose(a: Candidate | null) {
    if (!this.editable() || this.chooserLoading()) return;
    this.closeChooser();
    this.selected = a;
    this.explicitSelection = true;
    this.draft = {ownerAgentId: a?.id ?? null};
    this.edit();
  }
  async search(page = 0) {
    if (!this.editable()) return;
    const e = this.epoch,
      n = ++this.searchEpoch;
    this.candidates.set(null);
    this.chooserLoading.set(false);
    const q = strip(this.query);
    if (!text(q, 100)) {
      this.chooserError.set(
        'Tên tìm kiếm phải là văn bản một dòng, tối đa 100 ký tự.',
      );
      return;
    }
    this.chooserLoading.set(true);
    this.chooserError.set('');
    this.candidates.set(null);
    const scope = this.scope();
    try {
      if (
        !(await this.authorize(e, n)) ||
        !this.active(e) ||
        n !== this.searchEpoch
      )
        return;
      const p = await this.api.candidates(scope, page, q, this.cancel);
      if (!this.active(e) || n !== this.searchEpoch) return;
      if (!validPage(p, this.scope(), page, q)) throw Error();
      this.page = page;
      this.candidates.set(p);
    } catch (x) {
      if (this.active(e) && n === this.searchEpoch) {
        if (
          (x instanceof HttpErrorResponse &&
            [401, 403, 404].includes(x.status)) ||
          (x instanceof AuthFailure &&
            ['unauthorized', 'not-found'].includes(x.kind))
        )
          this.fail(x, 'Không thể tải danh sách Agent.');
        else
          this.chooserError.set(
            'Không thể tải danh sách Agent. Thử tìm kiếm lại.',
          );
      }
    } finally {
      if (this.active(e) && n === this.searchEpoch)
        this.chooserLoading.set(false);
    }
  }
  private async checkCandidate(e: number) {
    if (!this.selected) return true;
    const a = await this.api.candidate(
      this.scope(),
      this.selected.id,
      this.cancel,
    );
    if (!this.active(e)) return false;
    if (!candidate(a) || a.fingerprint !== this.selected.fingerprint) {
      this.selected = null;
      this.explicitSelection = false;
      this.acknowledged = false;
      this.phase.set('edit');
      this.error.set(
        'Thông tin Agent đã thay đổi. Tìm và chọn lại Agent rồi xem lại.',
      );
      return false;
    }
    return true;
  }
  async review() {
    this.acknowledged = false;
    if (!this.editable() || !this.body() || !this.dirty()) return;
    await this.revalidate();
    const e = this.epoch;
    if (!this.editable()) return;
    this.busy.set(true);
    try {
      if (!(await this.checkCandidate(e))) return;
      if (this.active(e)) {
        this.phase.set('review');
        this.focusHeading();
      }
    } catch (x) {
      if (this.active(e)) this.fail(x, 'Không thể xác minh Agent.');
    } finally {
      if (this.active(e)) this.busy.set(false);
    }
  }
  back() {
    this.acknowledged = false;
    this.phase.set('edit');
    this.focusHeading();
  }
  describeOwner(owner: Values | null | undefined): string {
    if (!owner) return 'Đang xác minh phân công hiện tại';
    if (owner.state === 'UNAVAILABLE')
      return 'Agent đã được cấu hình nhưng không còn khả dụng trong team này';
    if (owner.state === 'UNASSIGNED') return 'Không gán Agent';
    const a = owner.agent!;
    return `${a.name} · ${a.level} · ${this.statusLabel(a.status)}`;
  }
  statusLabel(status: string): string {
    const labels: Record<string, string> = {
      DRAFT: 'Bản nháp',
      ACTIVE: 'Đang hoạt động',
      SUSPENDED: 'Tạm ngừng',
      ARCHIVED: 'Đã lưu trữ',
      INACTIVE: 'Ngừng hoạt động',
      DISABLED: 'Đã vô hiệu hóa',
    };
    return labels[status] ?? 'Trạng thái khác';
  }
  blockerLabel(blocker: string): string {
    const labels: Record<string, string> = {
      TASK_NOT_DRAFT: 'Task không còn ở trạng thái bản nháp.',
      TEAM_ARCHIVED: 'Team đã được lưu trữ.',
      MISSION_NOT_EDITABLE:
        'Trạng thái mission hiện tại không cho phép sửa phân công.',
      INTAKE_PAUSED: 'Mission đang tạm dừng tiếp nhận thay đổi.',
      REVISION_EXHAUSTED:
        'Phân công đã đạt giới hạn phiên bản và không thể cập nhật thêm.',
      UNKNOWN_STATE:
        'Không thể xác minh trạng thái task hoặc dữ liệu cha. Vui lòng tải lại dữ liệu.',
    };
    return labels[blocker] ?? labels['UNKNOWN_STATE'];
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
      if (!(await this.checkCandidate(e)) || !this.acknowledged) return;
      this.locator = {
        ...this.scope(),
        actor: this.actor!,
        key: crypto.randomUUID(),
      };
      this.frozen = {
        body: Object.freeze(structuredClone(b)),
        before: Object.freeze(structuredClone(c.owner)),
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
    this.explicitSelection = false;
    this.selected = null;
    this.source = null;
    this.draft = this.empty();
    this.acknowledged = false;
    this.stale.set(false);
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
    if (
      !p ||
      (replay && !this.canReplay()) ||
      this.busy() ||
      this.loading() ||
      this.phase() !== 'unknown'
    )
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
  openChooser() {
    if (!this.editable()) return;
    const dialog = this.element.nativeElement.querySelector<HTMLDialogElement>(
      '[data-testid="owner-chooser"]',
    );
    if (!dialog || dialog.open) return;
    try {
      if (!dialog.isConnected || typeof dialog.showModal !== 'function') return;
      dialog.showModal();
      void this.search();
    } catch {
      this.error.set('Không thể mở bộ chọn Agent. Vui lòng thử lại.');
    }
  }
  closeChooser() {
    const dialog = this.element.nativeElement.querySelector<HTMLDialogElement>(
      '[data-testid="owner-chooser"]',
    );
    if (dialog?.open && typeof dialog.close === 'function') {
      try {
        dialog.close();
      } catch {
        /* Dialog may have been detached. */
      }
    }
    this.chooserClosed();
  }
  chooserClosed() {
    this.searchEpoch++;
    this.chooserLoading.set(false);
    this.chooserError.set('');
    this.candidates.set(null);
    this.element.nativeElement
      .querySelector<HTMLElement>('#owner-chooser-trigger')
      ?.focus();
  }
  toggleMenu() {
    const dialog = this.element.nativeElement.querySelector<HTMLDialogElement>(
      '#mission-intake-menu',
    );
    if (
      !dialog?.isConnected ||
      dialog.open ||
      typeof dialog.showModal !== 'function'
    )
      return;
    try {
      dialog.showModal();
      this.menuOpen.set(true);
    } catch {
      this.menuOpen.set(false);
    }
  }
  closeMenu() {
    this.menuOpen.set(false);
    const dialog = this.element.nativeElement.querySelector<HTMLDialogElement>(
      '#mission-intake-menu',
    );
    if (dialog?.open && typeof dialog.close === 'function') {
      try {
        dialog.close();
      } catch {
        /* Dialog may have been detached. */
      }
    }
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="mission-intake-menu"]')
      ?.focus();
  }
}

import {HttpErrorResponse} from '@angular/common/http';
import {DatePipe} from '@angular/common';
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
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {TeamSettingsService} from './team-settings.service';
import {TeamSettingsNavigationState} from './team-settings-navigation.guard';
import {
  MAX_REVISION,
  SettingsAgent,
  SettingsBody,
  SettingsCandidates,
  SettingsContext,
  SettingsLocator,
  SettingsReceipt,
  SettingsState,
  budget,
  desiredState,
  knownAgent,
  sameState,
  safeText,
  settingsUuid,
  strip,
  validSettingsBody,
  validSettingsCandidates,
  validSettingsContext,
  validSettingsReceipt,
} from './team-settings-validation';

interface Draft {
  name: string;
  purpose: string;
  description: string | null;
  managerAgentId: string | null;
  selected: string[];
  concurrency: string;
  monthlyBudget: string;
}
@Component({
  selector: 'app-team-settings',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe],
  templateUrl: './team-settings.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './team-settings.component.css',
  ],
})
export class TeamSettingsComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(TeamSettingsService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private allowedNavigationId = 0;
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly navigation = inject(TeamSettingsNavigationState);
  private epoch = 0;
  private candidateEpoch = 0;
  private actor = this.auth.user()?.id;
  private original = '';
  private frozen: {body: SettingsBody; before: SettingsState} | null = null;
  private locator: SettingsLocator | null = null;
  private authorityBusy = false;
  private authorityAgain = false;
  id = '';
  teamId = '';
  readonly context = signal<SettingsContext | null>(null);
  readonly workspace = signal<Workspace | null>(null);
  readonly candidates = signal<SettingsCandidates | null>(null);
  readonly receipt = signal<SettingsReceipt | null>(null);
  readonly loading = signal(false);
  readonly candidateLoading = signal(false);
  readonly busy = signal(false);
  readonly denied = signal(false);
  readonly verified = signal(false);
  readonly error = signal('');
  readonly candidateError = signal('');
  readonly stale = signal(false);
  readonly phase = signal<'edit' | 'review' | 'unknown' | 'result'>('edit');
  readonly tab = signal('info');
  readonly menuOpen = signal(false);
  readonly tabs = [
    {id: 'info', label: 'Thông tin'},
    {id: 'members', label: 'Thành viên & Manager'},
    {id: 'limits', label: 'Giới hạn'},
  ];
  draft: Draft = this.empty();
  acknowledged = false;
  search = '';
  query = '';
  page = 0;
  readonly additions = signal<SettingsAgent[]>([]);
  constructor() {
    this.navigation.active = this;
    effect(() => {
      const id = this.auth.user()?.id;
      if (id !== this.actor) {
        this.actor = id;
        this.loseAuthority();
      }
    });
    this.destroy.onDestroy(() => {
      this.clearPrivate();
      if (this.navigation.active === this) this.navigation.active = null;
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(p => {
      this.clearPrivate();
      this.locator = null;
      // An accepted identity change starts a fresh page, even from UNKNOWN.
      // Same-identity refresh still preserves the unresolved operation in load().
      this.phase.set('edit');
      this.tab.set('info');
      this.id = p.get('id') ?? '';
      this.teamId = p.get('teamId') ?? '';
      this.actor = this.auth.user()?.id;
      void this.load();
    });
  }
  private empty(): Draft {
    return {
      name: '',
      purpose: '',
      description: null,
      managerAgentId: null,
      selected: [],
      concurrency: '',
      monthlyBudget: '',
    };
  }
  private clearPrivate() {
    this.epoch++;
    this.candidateEpoch++;
    this.context.set(null);
    this.workspace.set(null);
    this.candidates.set(null);
    this.receipt.set(null);
    this.additions.set([]);
    this.frozen = null;
    this.draft = this.empty();
    this.original = '';
    this.acknowledged = false;
    this.verified.set(false);
    this.loading.set(false);
    this.busy.set(false);
    this.candidateLoading.set(false);
    this.search = '';
    this.query = '';
    this.page = 0;
    this.error.set('');
    this.candidateError.set('');
    this.closeMenu();
  }
  private loseAuthority() {
    const pending = this.locator;
    this.clearPrivate();
    this.locator = pending;
    this.denied.set(true);
    this.phase.set(pending ? 'unknown' : 'edit');
  }
  private active(epoch: number) {
    return (
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      this.actor === this.auth.user()?.id
    );
  }
  private async authorize(epoch: number): Promise<boolean> {
    const actor = this.actor;
    const session = await this.auth.session();
    if (!this.active(epoch)) return false;
    if (!actor || session?.id !== actor) {
      this.loseAuthority();
      return false;
    }
    const workspace = await this.auth.getWorkspace(this.id);
    if (!this.active(epoch)) return false;
    if (!workspace || workspace.id !== this.id || workspace.role !== 'ADMIN') {
      this.loseAuthority();
      return false;
    }
    this.workspace.set(workspace);
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
  async load() {
    if (this.busy() || this.phase() === 'unknown') return;
    this.acknowledged = false;
    if (this.dirty() && !window.confirm('Bỏ thay đổi chưa lưu và tải lại?'))
      return;
    this.clearPrivate();
    this.phase.set('edit');
    this.stale.set(false);
    this.denied.set(false);
    if (!settingsUuid(this.id) || !settingsUuid(this.teamId)) {
      this.error.set('Team không khả dụng.');
      return;
    }
    const epoch = this.epoch;
    this.loading.set(true);
    try {
      if (!(await this.authorize(epoch))) return;
      const data = await this.api.context(this.id, this.teamId);
      if (!this.active(epoch)) return;
      if (!validSettingsContext(data, this.id, this.teamId))
        throw Error('Malformed context');
      this.context.set(data);
      const t = data.team;
      this.draft = {
        name: t.name,
        purpose: t.purpose ?? '',
        description: t.description,
        managerAgentId: t.managerAgentId,
        selected: [
          ...t.agentIds,
          ...(t.managerAgentId ? [t.managerAgentId] : []),
        ].sort(),
        concurrency: t.concurrency === null ? '' : String(t.concurrency),
        monthlyBudget: t.monthlyBudget === null ? '' : String(t.monthlyBudget),
      };
      this.original = JSON.stringify(this.draft);
    } catch (e) {
      if (this.active(epoch))
        this.fail(e, 'Không thể tải cấu hình. Thử lại bằng GET.');
    } finally {
      if (this.active(epoch)) this.loading.set(false);
    }
  }
  dirty() {
    return !!this.original && JSON.stringify(this.draft) !== this.original;
  }
  canLeave() {
    this.acknowledged = false;
    if (!this.dirty() && this.phase() !== 'unknown' && !this.busy())
      return true;
    const navigationId = this.router.getCurrentNavigation()?.id;
    if (navigationId && navigationId === this.allowedNavigationId) return true;
    const leave = window.confirm(
      'Rời trang sẽ bỏ bản nháp và thông tin khôi phục chưa lưu. Tiếp tục?',
    );
    if (leave && navigationId) this.allowedNavigationId = navigationId;
    return leave;
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(
    event: BeforeUnloadEvent,
  ) {
    this.acknowledged = false;
    if (this.dirty() || this.phase() === 'unknown' || this.busy()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
  @HostListener('window:focus') onFocus() {
    void this.revalidate();
  }
  @HostListener('document:visibilitychange') onVisibility() {
    if (document.visibilityState === 'visible') void this.revalidate();
  }
  async revalidate() {
    // Keep observing actual authorization while POST is in flight. Never gate on busy().
    if (this.destroy.destroyed) return;
    if (this.authorityBusy) {
      this.authorityAgain = true;
      return;
    }
    this.acknowledged = false;
    this.authorityBusy = true;
    this.verified.set(false);
    const epoch = this.epoch;
    try {
      if (!(await this.authorize(epoch))) return;
      if (
        this.context() &&
        this.phase() !== 'unknown' &&
        this.phase() !== 'result'
      ) {
        const data = await this.api.context(this.id, this.teamId);
        if (!this.active(epoch)) return;
        if (!validSettingsContext(data, this.id, this.teamId))
          throw Error('Malformed context');
        if (
          data.revision !== this.context()!.revision ||
          data.fingerprint !== this.context()!.fingerprint
        )
          this.conflict();
      }
    } catch (e) {
      if (this.active(epoch))
        this.fail(e, 'Không thể xác minh quyền hiện tại. Thử kiểm tra lại.');
    } finally {
      this.authorityBusy = false;
      if (this.authorityAgain) {
        this.authorityAgain = false;
        void this.revalidate();
      }
    }
  }
  private conflict() {
    this.stale.set(true);
    this.acknowledged = false;
    this.phase.set('edit');
    this.error.set(
      'Cấu hình nguồn đã thay đổi. Bản nháp được giữ; tải lại dữ liệu rồi xem lại.',
    );
  }
  editable() {
    return (
      !!this.context()?.canEdit &&
      this.context()!.revision < MAX_REVISION &&
      this.verified() &&
      !this.stale() &&
      !this.busy() &&
      !this.loading() &&
      this.phase() === 'edit'
    );
  }
  membershipEditable() {
    return this.editable() && !!this.context()?.canChangeMembership;
  }
  edit() {
    this.acknowledged = false;
    this.error.set('');
  }
  selectTab(tab: string) {
    if (this.busy()) return;
    this.acknowledged = false;
    this.tab.set(tab);
    if (this.phase() === 'review') this.phase.set('edit');
    if (
      tab === 'members' &&
      this.context()?.canChangeMembership &&
      !this.candidates()
    )
      void this.findCandidates(0);
  }
  allAgents(): SettingsAgent[] {
    return [...(this.context()?.members ?? []), ...this.additions()];
  }
  selectedAgents() {
    return this.allAgents().filter(a => this.draft.selected.includes(a.id));
  }
  agentName(id: string | null) {
    return id === null
      ? 'Không có'
      : (this.allAgents().find(a => a.id === id)?.name ?? id);
  }
  agentLabel(a: SettingsAgent) {
    return knownAgent(a)
      ? `${a.level} · ${a.status}`
      : 'Cấu hình cũ chưa được hỗ trợ';
  }
  managers() {
    return this.selectedAgents().filter(
      a =>
        a.id === this.context()?.team.managerAgentId ||
        (a.level === 'MANAGER' && ['DRAFT', 'ACTIVE'].includes(a.status)),
    );
  }
  toggleMember(a: SettingsAgent) {
    if (!this.membershipEditable()) return;
    const selected = this.draft.selected.includes(a.id);
    const existing = this.context()?.members.find(m => m.id === a.id);
    if (selected && existing && !existing.canRemove) return;
    if (!selected && this.draft.selected.length >= 200) {
      this.error.set('Tối đa 200 thành viên bao gồm Manager.');
      return;
    }
    this.edit();
    this.draft.selected = selected
      ? this.draft.selected.filter(id => id !== a.id)
      : [...this.draft.selected, a.id].sort();
    if (selected && this.draft.managerAgentId === a.id)
      this.draft.managerAgentId = null;
  }
  async findCandidates(page = 0) {
    if (
      !this.context()?.canChangeMembership ||
      this.busy() ||
      this.phase() !== 'edit' ||
      !Number.isInteger(page) ||
      page < 0 ||
      page > 100000
    )
      return;
    const query = strip(this.search);
    if (!safeText(query, 100)) {
      this.candidateError.set('Tìm kiếm tối đa 100 ký tự hợp lệ.');
      return;
    }
    const epoch = this.epoch,
      request = ++this.candidateEpoch;
    this.candidateLoading.set(true);
    this.candidateError.set('');
    this.candidates.set(null);
    try {
      if (!(await this.authorize(epoch))) return;
      const data = await this.api.candidates(this.id, this.teamId, page, query);
      if (!this.active(epoch) || request !== this.candidateEpoch) return;
      if (
        !validSettingsCandidates(
          data,
          this.id,
          this.teamId,
          page,
          this.context()!.members.map(a => a.id),
        )
      )
        throw Error('Malformed candidates');
      this.page = page;
      this.query = query;
      this.candidates.set(data);
      const additions = new Map(this.additions().map(a => [a.id, a]));
      data.items.forEach(a => additions.set(a.id, a));
      this.additions.set([...additions.values()]);
    } catch (e) {
      if (this.active(epoch) && request === this.candidateEpoch) {
        this.fail(e, '');
        this.candidateError.set('Danh sách ứng viên không khả dụng. Thử lại.');
      }
    } finally {
      if (this.active(epoch) && request === this.candidateEpoch)
        this.candidateLoading.set(false);
    }
  }
  body(): SettingsBody | null {
    const c = this.context();
    if (!c) return null;
    if (
      !/^[0-9]+$/.test(this.draft.concurrency) ||
      !/^[0-9]+(?:\.[0-9]{1,2})?$/.test(this.draft.monthlyBudget)
    )
      return null;
    const b: SettingsBody = {
      expectedRevision: c.revision,
      expectedFingerprint: c.fingerprint,
      name: strip(this.draft.name),
      purpose: strip(this.draft.purpose),
      description: this.draft.description,
      managerAgentId: this.draft.managerAgentId,
      agentIds: this.draft.selected
        .filter(id => id !== this.draft.managerAgentId)
        .sort(),
      intake: 'MANUAL',
      concurrency: Number(this.draft.concurrency),
      monthlyBudget: Number(this.draft.monthlyBudget),
      acknowledgement: true,
    };
    if (!validSettingsBody(b) || !budget(b.monthlyBudget)) return null;
    if (
      !c.canChangeMembership &&
      (JSON.stringify(b.agentIds) !== JSON.stringify(c.team.agentIds) ||
        b.managerAgentId !== c.team.managerAgentId)
    )
      return null;
    if (
      b.managerAgentId !== c.team.managerAgentId &&
      b.managerAgentId !== null &&
      !this.managers().some(
        a =>
          a.id === b.managerAgentId &&
          a.level === 'MANAGER' &&
          ['DRAFT', 'ACTIVE'].includes(a.status),
      )
    )
      return null;
    if (
      c.members.some(m => !this.draft.selected.includes(m.id) && !m.canRemove)
    )
      return null;
    return b;
  }
  review() {
    this.acknowledged = false;
    const b = this.body(),
      c = this.context();
    if (!this.editable() || !b || !c) {
      this.error.set(
        'Kiểm tra Team name, Purpose, thành viên và các giới hạn hợp lệ.',
      );
      return;
    }
    if (sameState(c.team, desiredState(b, c.team.status))) {
      this.error.set('Không có thay đổi để xem lại.');
      return;
    }
    this.error.set('');
    this.phase.set('review');
    this.focusHeading();
  }
  back() {
    this.acknowledged = false;
    this.phase.set('edit');
    this.focusHeading();
  }
  diffRows(
    before = this.context()?.team,
    after = this.body() && this.context()
      ? desiredState(this.body()!, this.context()!.team.status)
      : null,
  ) {
    if (!before || !after) return [];
    const show = (v: unknown) => (v === null ? 'Chưa có' : String(v));
    return [
      {label: 'Team name', before: before.name, after: after.name},
      {
        label: 'Purpose',
        before: show(before.purpose),
        after: show(after.purpose),
      },
      {
        label: 'Description',
        before: show(before.description),
        after: show(after.description),
      },
      {
        label: 'Manager',
        before: this.agentName(before.managerAgentId),
        after: this.agentName(after.managerAgentId),
      },
      {
        label: 'Members',
        before:
          [
            ...before.agentIds,
            ...(before.managerAgentId ? [before.managerAgentId] : []),
          ]
            .map(id => this.agentName(id))
            .join(', ') || 'Không có',
        after:
          [
            ...after.agentIds,
            ...(after.managerAgentId ? [after.managerAgentId] : []),
          ]
            .map(id => this.agentName(id))
            .join(', ') || 'Không có',
      },
      {
        label: 'Concurrency',
        before: show(before.concurrency),
        after: show(after.concurrency),
      },
      {
        label: 'Monthly budget',
        before: show(before.monthlyBudget) + ' ' + show(before.currency),
        after: show(after.monthlyBudget) + ' ' + show(after.currency),
      },
      {label: 'Intake', before: show(before.intake), after: show(after.intake)},
      {label: 'Status', before: before.status, after: after.status},
    ];
  }
  membershipDiff(added: boolean) {
    const c = this.context();
    if (!c) return [];
    const current = c.members.map(m => m.id);
    return added
      ? this.draft.selected.filter(id => !current.includes(id))
      : current.filter(id => !this.draft.selected.includes(id));
  }
  blockerLabel(code: string) {
    const labels: Record<string, string> = {
      TEAM_ARCHIVED: 'Team đã lưu trữ; cài đặt chỉ đọc.',
      TEAM_SIZE_UNSUPPORTED:
        'Team có hơn 200 thành viên; chưa hỗ trợ lưu thay đổi.',
      UNSUPPORTED_CURRENCY: 'Đơn vị tiền tệ hiện tại chưa được hỗ trợ.',
      AGENT_NOT_DRAFT: 'Chỉ có thể bỏ Agent ở trạng thái DRAFT.',
      AGENT_REPORTS_TO: 'Agent có liên kết báo cáo cần được giữ nguyên.',
      AGENT_UNSUPPORTED: 'Cấu hình cũ của Agent chưa được hỗ trợ.',
      AGENT_REFERENCED: 'Agent có liên kết báo cáo được bảo vệ.',
      AGENT_HISTORY_REFERENCED: 'Agent có tham chiếu lịch sử được bảo vệ.',
      TEAM_NOT_DRAFT: 'Team không ở trạng thái DRAFT; giữ nguyên thành viên.',
    };
    return labels[code] ?? 'Thay đổi này bị chặn để bảo vệ dữ liệu hiện có.';
  }
  removalReason(codes: string[]) {
    return codes.map(code => this.blockerLabel(code)).join(' ');
  }
  retainedMembers() {
    return (
      this.context()
        ?.members.filter(m => this.draft.selected.includes(m.id))
        .map(m => m.id) ?? []
    );
  }
  async save() {
    if (
      this.busy() ||
      this.phase() !== 'review' ||
      !this.acknowledged ||
      this.stale() ||
      !this.verified()
    )
      return;
    const body = this.body(),
      baseline = this.context();
    if (!body || !baseline || !baseline.canEdit) return;
    const epoch = this.epoch;
    this.busy.set(true);
    this.error.set('');
    try {
      // Fetch CSRF first so the final fresh authority + Context immediately precede POST.
      const headers = await this.api.csrf();
      if (!this.active(epoch) || !(await this.authorize(epoch))) return;
      const fresh = await this.api.context(this.id, this.teamId);
      if (!this.active(epoch)) return;
      if (!validSettingsContext(fresh, this.id, this.teamId))
        throw Error('Malformed context');
      if (
        fresh.revision !== baseline.revision ||
        fresh.fingerprint !== baseline.fingerprint ||
        !fresh.canEdit
      ) {
        this.conflict();
        return;
      }
      if (!this.acknowledged) return;
      this.locator = {
        actor: this.actor!,
        org: this.id,
        team: this.teamId,
        key: crypto.randomUUID(),
      };
      this.frozen = {
        body: structuredClone(body),
        before: structuredClone(baseline.team),
      };
      this.phase.set('unknown');
      this.acknowledged = false;
      await this.postFrozen(epoch, headers);
    } catch (e) {
      if (this.active(epoch))
        this.fail(e, 'Không thể xác minh trước khi lưu. Vui lòng xem lại.');
    } finally {
      if (this.active(epoch)) this.busy.set(false);
    }
  }
  private async postFrozen(
    epoch: number,
    headers: import('@angular/common/http').HttpHeaders,
  ) {
    const p = this.locator!,
      f = this.frozen!;
    try {
      const data = await this.api.save(p.org, p.team, f.body, p.key, headers);
      if (!this.active(epoch)) return;
      if (!this.verified()) {
        this.error.set(
          'Cần xác minh quyền hiện tại trước khi hiển thị biên nhận.',
        );
        return;
      }
      if (!validSettingsReceipt(data, p, f.body, f.before))
        throw Error('Unknown response');
      this.acceptReceipt(data);
    } catch (e) {
      if (!this.active(epoch)) return;
      if (
        e instanceof HttpErrorResponse &&
        [400, 409, 415, 422].includes(e.status)
      ) {
        this.frozen = null;
        this.locator = null;
        this.phase.set('edit');
        this.stale.set(true);
        this.error.set(
          'Yêu cầu bị từ chối. Tải lại cấu hình và xem lại trước khi lưu.',
        );
      } else {
        this.phase.set('unknown');
        this.fail(
          e,
          'Chưa xác định kết quả. Kiểm tra biên nhận trước khi quyết định gửi lại yêu cầu gốc.',
        );
      }
    }
  }
  private acceptReceipt(data: SettingsReceipt) {
    this.receipt.set(data);
    this.phase.set('result');
    this.frozen = null;
    this.locator = null;
    this.original = '';
    this.acknowledged = false;
    this.error.set('');
    this.focusHeading();
  }
  canReplay() {
    return !!this.frozen && !this.denied();
  }
  async recover(replay = false) {
    if (this.busy() || !this.locator || this.phase() !== 'unknown') return;
    const epoch = this.epoch,
      p = this.locator;
    this.busy.set(true);
    this.error.set('');
    this.acknowledged = false;
    try {
      const headers = replay ? await this.api.csrf() : null;
      if (!this.active(epoch) || !(await this.authorize(epoch))) return;
      if (
        this.actor !== p.actor ||
        this.id !== p.org ||
        this.teamId !== p.team
      ) {
        this.loseAuthority();
        return;
      }
      if (replay) {
        if (this.frozen && headers) await this.postFrozen(epoch, headers);
        return;
      }
      const data = await this.api.receipt(p.org, p.team, p.key);
      if (!this.active(epoch)) return;
      if (
        data === null ||
        !validSettingsReceipt(data, p, this.frozen?.body, this.frozen?.before)
      ) {
        this.error.set(
          'Chưa tìm được biên nhận hợp lệ. Kết quả vẫn chưa xác định.',
        );
        return;
      }
      this.acceptReceipt(data);
    } catch (e) {
      if (this.active(epoch))
        this.fail(
          e,
          'Không thể kiểm tra biên nhận. Kết quả vẫn chưa xác định.',
        );
    } finally {
      if (this.active(epoch)) this.busy.set(false);
    }
  }
  newRead() {
    if (this.phase() === 'result') {
      this.phase.set('edit');
      void this.load();
    }
  }
  private focusHeading() {
    queueMicrotask(() =>
      this.element.nativeElement.querySelector<HTMLElement>('h1')?.focus(),
    );
  }
  toggleMenu() {
    if (this.menuOpen()) this.closeMenu();
    else {
      this.menuOpen.set(true);
      this.element.nativeElement
        .querySelector<HTMLDialogElement>('dialog')
        ?.showModal();
    }
  }
  closeMenu() {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('dialog')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="team-settings-menu"]')
      ?.focus();
  }
}

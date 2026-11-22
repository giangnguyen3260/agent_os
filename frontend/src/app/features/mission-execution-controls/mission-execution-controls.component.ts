import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {Component, HostListener, effect, inject, signal} from '@angular/core';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {firstValueFrom, timeout} from 'rxjs';
import {AuthService} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
type Action = 'PAUSE' | 'RESUME' | 'REQUEST_CANCEL' | 'ACK_CANCEL';
interface Work {
  queued: number;
  claimed: number;
  unknownDispatches: number;
  activeAttempts: number;
  unknownAttempts: number;
  liveClaims: number;
  unsettledReservations: number;
  handedOffRequests: number;
}
interface Run {
  taskId: string;
  runId: string;
  title: string;
  taskStatus: string;
  status: string;
  revision: string;
}
interface Snapshot {
  evidenceSha256: string;
  organizationId: string;
  teamId: string;
  missionId: string;
  title: string;
  missionStatus: string;
  controlState: string | null;
  controlRevision: string | null;
  planRevision: string | null;
  runs: Run[];
  work: Work;
  canPause: boolean;
  canResume: boolean;
  canRequestCancel: boolean;
  canAcknowledgeCancel: boolean;
  safelyPaused: boolean;
  blockers: string[];
  fingerprint: string;
}
interface Command {
  idempotencyKey: string;
  expectedFingerprint: string;
  action: Action;
}
interface Receipt {
  snapshotSha256: string;
  organizationId: string;
  teamId: string;
  missionId: string;
  idempotencyKey: string;
  action: Action;
  sourceFingerprint: string;
  controlRevisionBefore: string;
  controlRevisionAfter: string;
  controlState: string;
  missionStatus: string;
  retiredQueuedDispatchIds: string[];
}
const revision = (v: unknown, zero = false): v is string =>
  typeof v === 'string' &&
  (zero ? /^(0|[1-9][0-9]{0,18})$/ : /^[1-9][0-9]{0,18}$/).test(v) &&
  BigInt(v) <= 9223372036854775807n;
const hash = (v: unknown): v is string =>
  typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
@Component({
  standalone: true,
  imports: [RouterLink],
  styleUrl: '../execution-review/execution-review.component.css',
  styles: [
    'article {border-top:1px solid #d4e1da;padding:12px 0;} p {overflow-wrap:anywhere;}',
  ],
  template: `<main>
    <h1>Điều khiển thực thi Mission</h1>
    <a
      [routerLink]="[
        '/workspace',
        org,
        'teams',
        team,
        'missions',
        mission,
        'plan-review',
      ]"
      >Về kế hoạch và Run</a
    >
    <p>
      Tạm dừng thực thi độc lập với việc khóa nhận Task mới. Request đã gửi vẫn
      cần ghi nhận kết quả và chi phí; tạm dừng không thu hồi request đã gửi.
    </p>
    <p role="status">{{ busy() ? 'Đang kiểm tra…' : message() }}</p>
    @if (receipt(); as saved) {
      <p role="status">
        Đã ghi nhận {{ label(saved.action) }} · Phiên bản
        {{ saved.controlRevisionAfter }} · Mission {{ saved.missionStatus }}
      </p>
    }
    @if (pending()) {
      <p>
        Kết quả lệnh chưa xác định. Gửi lại đúng lệnh đang giữ để đối chiếu;
        chưa tạo lệnh mới.
      </p>
      <button (click)="send()" [disabled]="busy()">
        Gửi lại đúng lệnh điều khiển
      </button>
    }
    <button (click)="load()" [disabled]="busy() || !!pending()">
      Kiểm tra trạng thái hiện tại
    </button>
    @if (data(); as s) {
      <h2>{{ s.title }}</h2>
      <p>
        Mission {{ s.missionStatus }} · Điều khiển
        {{ s.controlState || 'Chưa có' }} · Phiên bản {{ s.controlRevision }}
      </p>
      @if (s.controlState === 'PAUSE_REQUESTED') {
        <p>
          {{
            s.safelyPaused
              ? 'Đã dừng tại ranh giới an toàn; không có yêu cầu đang chạy hoặc chưa đối soát.'
              : 'Đã yêu cầu tạm dừng; đang chờ worker hoặc kết quả chưa đối soát.'
          }}
        </p>
      }
      @if (
        s.controlState === 'CANCEL_REQUESTED' && s.missionStatus !== 'CANCELLED'
      ) {
        <p>
          Đã yêu cầu hủy. Chỉ xác nhận hủy sau khi mọi yêu cầu và chi phí đã
          được xử lý xong.
        </p>
      }
      <ul>
        <li>Đã xếp hàng: {{ s.work.queued }}</li>
        <li>Worker đã nhận: {{ s.work.claimed }}</li>
        <li>Request đã gửi đang chờ kết quả: {{ s.work.handedOffRequests }}</li>
        <li>Attempt đang hoạt động: {{ s.work.activeAttempts }}</li>
        <li>Kết quả request chưa rõ: {{ s.work.unknownDispatches }}</li>
        <li>Attempt chưa rõ: {{ s.work.unknownAttempts }}</li>
        <li>Claim còn hiệu lực: {{ s.work.liveClaims }}</li>
        <li>
          Khoản giữ chi phí chưa đối soát: {{ s.work.unsettledReservations }}
        </li>
      </ul>
      @if (s.blockers.length) {
        <ul>
          @for (b of s.blockers; track b) {
            <li>{{ reason(b) }}</li>
          }
        </ul>
      }
      @for (r of s.runs; track r.runId) {
        <article>
          <h3>{{ r.title }}</h3>
          <a [routerLink]="['/workspace', org, 'execution-runs', r.runId]"
            >Xem Run</a
          >
          <p>
            Task {{ r.taskStatus }} · Run {{ r.status }} · Phiên bản
            {{ r.revision }}
          </p>
        </article>
      }
      @if (!pending()) {
        @for (a of actions(); track a) {
          <button (click)="choose(a)" [disabled]="busy()">
            {{ label(a) }}
          </button>
        }
        @if (selected(); as action) {
          <h2>Xác nhận: {{ label(action) }}</h2>
          <p>{{ explanation(action) }}</p>
          <label
            ><input
              type="checkbox"
              [checked]="confirmed()"
              (change)="confirmed.set($any($event.target).checked)"
              [disabled]="busy()"
            />Tôi xác nhận trạng thái hiện tại và tác động của lệnh này.</label
          >
          <button (click)="apply()" [disabled]="busy() || !confirmed()">
            Gửi lệnh đã xác nhận
          </button>
        }
      }
    }
  </main>`,
})
export class MissionExecutionControlsComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private account = this.auth.user()?.id;
  private generation = 0;
  private reviewedAt = 0;
  private pendingSource: Snapshot | null = null;
  org = '';
  team = '';
  mission = '';
  readonly data = signal<Snapshot | null>(null);
  readonly busy = signal(false);
  readonly message = signal('');
  readonly selected = signal<Action | null>(null);
  readonly confirmed = signal(false);
  readonly pending = signal<Command | null>(null);
  readonly receipt = signal<Receipt | null>(null);
  constructor() {
    effect(() => {
      const account = this.auth.user()?.id;
      if (account !== this.account) {
        this.account = account;
        this.clear();
        this.message.set(
          'Phiên đăng nhập đã thay đổi. Kiểm tra lại quyền hiện tại.',
        );
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(p => {
      this.clear();
      this.org = p.get('id') ?? '';
      this.team = p.get('teamId') ?? '';
      this.mission = p.get('missionId') ?? '';
      void this.load();
    });
  }
  private clear() {
    this.generation++;
    this.busy.set(false);
    this.data.set(null);
    this.selected.set(null);
    this.confirmed.set(false);
    this.pending.set(null);
    this.pendingSource = null;
    this.receipt.set(null);
  }
  private base() {
    return `/api/v1/organizations/${this.org}/teams/${this.team}/missions/${this.mission}/execution-controls`;
  }
  private active(g: number, a: string | undefined) {
    return g === this.generation && a === this.auth.user()?.id;
  }
  private scope(s: {
    organizationId: string;
    teamId: string;
    missionId: string;
  }) {
    return (
      s?.organizationId === this.org &&
      s.teamId === this.team &&
      s.missionId === this.mission
    );
  }
  label(a: Action) {
    return {
      PAUSE: 'Tạm dừng thực thi',
      RESUME: 'Tiếp tục thực thi',
      REQUEST_CANCEL: 'Yêu cầu hủy Mission',
      ACK_CANCEL: 'Xác nhận hủy Mission',
    }[a];
  }
  explanation(a: Action) {
    return {
      PAUSE:
        'Giữ các Start chưa gửi trong hàng đợi, chặn claim và request mới. Request đã gửi vẫn có thể hoàn tất.',
      RESUME:
        'Cho phép các Start đã xếp hàng tiếp tục chạy và có thể phát sinh chi phí đã được cho phép. Công việc chưa gửi đã được worker trả về READY cần Start mới.',
      REQUEST_CANCEL:
        'Hủy các Start chưa được worker sử dụng và chuyển Run chưa hoàn tất sang chờ hủy. Giữ nguyên output đã hoàn tất. Chưa xác nhận Mission CANCELLED ở bước này.',
      ACK_CANCEL:
        'Xác nhận Mission CANCELLED sau khi không còn request, Attempt, claim hoặc khoản giữ chi phí chưa xử lý. Không mở lại Mission đã hủy.',
    }[a];
  }
  reason(b: string) {
    return (
      (
        {
          INCONSISTENT_RUNTIME_SCOPE:
            'Trạng thái Task và Run không khớp; cần đối soát trước khi điều khiển.',
          PARENT_UNAVAILABLE: 'Workspace hoặc Team không hoạt động.',
          MISSION_STATE_UNAVAILABLE:
            'Trạng thái Mission không hỗ trợ điều khiển.',
          CONTROL_UNAVAILABLE: 'Không có dữ liệu điều khiển hợp lệ.',
          UNSUPPORTED_SCOPE:
            'Cần toàn bộ kế hoạch đã tiếp nhận hợp lệ; chưa hỗ trợ dữ liệu cũ.',
          PLAN_CONFIGURATION_UNAVAILABLE:
            'Cấu hình đã thay đổi so với kế hoạch được tiếp nhận.',
          WORKER_DRAIN_PENDING:
            'Chờ worker xử lý tới ranh giới an toàn trước khi tiếp tục hoặc hủy.',
          RECONCILIATION_PENDING:
            'Còn công việc hoặc chi phí chưa đối soát; chưa thể xác nhận hủy.',
          QUEUED_EVIDENCE_UNAVAILABLE:
            'Không chứng minh được request xếp hàng chưa được sử dụng.',
          REVISION_HEADROOM_UNAVAILABLE:
            'Không đủ phiên bản để thực hiện chuỗi chuyển trạng thái an toàn.',
          SCOPE_LIMIT_EXCEEDED: 'Phạm vi vượt giới hạn xem xét.',
        } as Record<string, string>
      )[b] ?? 'Điều kiện thực thi chưa hỗ trợ.'
    );
  }
  actions(): Action[] {
    const s = this.data();
    if (!s) return [];
    return [
      s.canPause ? 'PAUSE' : null,
      s.canResume ? 'RESUME' : null,
      s.canRequestCancel ? 'REQUEST_CANCEL' : null,
      s.canAcknowledgeCancel ? 'ACK_CANCEL' : null,
    ].filter((a): a is Action => a !== null);
  }
  private valid(s: Snapshot) {
    if (
      !this.scope(s) ||
      typeof s.title !== 'string' ||
      ![
        'RUNNING',
        'WAITING_APPROVAL',
        'REVIEW',
        'COMPLETED',
        'FAILED',
        'CANCELLED',
      ].includes(s.missionStatus) ||
      !hash(s.fingerprint) ||
      !hash(s.evidenceSha256) ||
      !(
        s.controlState === null ||
        ['OPEN', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED'].includes(s.controlState)
      ) ||
      !(s.controlRevision === null || revision(s.controlRevision)) ||
      !(s.planRevision === null || revision(s.planRevision)) ||
      !Array.isArray(s.runs) ||
      s.runs.length > 200 ||
      !Array.isArray(s.blockers) ||
      s.blockers.length > 20 ||
      s.blockers.some(b => typeof b !== 'string') ||
      !s.work
    )
      return false;
    if (
      ![
        'queued',
        'claimed',
        'unknownDispatches',
        'activeAttempts',
        'unknownAttempts',
        'liveClaims',
        'unsettledReservations',
        'handedOffRequests',
      ].every(
        k =>
          Number.isSafeInteger(s.work[k as keyof Work]) &&
          s.work[k as keyof Work] >= 0,
      ) ||
      [
        'canPause',
        'canResume',
        'canRequestCancel',
        'canAcknowledgeCancel',
        'safelyPaused',
      ].some(k => typeof s[k as keyof Snapshot] !== 'boolean')
    )
      return false;
    if (
      s.runs.some(
        r =>
          !uuid(r.runId) ||
          !uuid(r.taskId) ||
          typeof r.title !== 'string' ||
          !revision(r.revision, true) ||
          ![
            'CREATED',
            'BLOCKED',
            'RUNNING',
            'WAITING',
            'IN_REVIEW',
            'REWORK_REQUIRED',
            'COMPLETED',
            'FAILED',
            'CANCELLING',
            'CANCELLED',
          ].includes(r.status) ||
          ![
            'DRAFT',
            'READY',
            'RUNNING',
            'WAITING',
            'BLOCKED',
            'IN_REVIEW',
            'COMPLETED',
            'FAILED',
            'CANCELLED',
          ].includes(r.taskStatus),
      ) ||
      new Set(s.runs.map(r => r.runId)).size !== s.runs.length ||
      new Set(s.runs.map(r => r.taskId)).size !== s.runs.length
    )
      return false;
    if (s.work.handedOffRequests > s.work.claimed) return false;
    const w = s.work,
      drained = w.claimed === 0 && w.activeAttempts === 0 && w.liveClaims === 0,
      reconciled =
        drained &&
        w.unknownDispatches === 0 &&
        w.unknownAttempts === 0 &&
        w.unsettledReservations === 0;
    if (s.safelyPaused && (s.controlState !== 'PAUSE_REQUESTED' || !reconciled))
      return false;
    if (
      (s.canPause ||
        s.canResume ||
        s.canRequestCancel ||
        s.canAcknowledgeCancel) &&
      (!['RUNNING', 'WAITING_APPROVAL'].includes(s.missionStatus) ||
        !s.runs.length ||
        s.controlRevision === null ||
        s.planRevision === null)
    )
      return false;
    return !(
      (s.canPause && s.controlState !== 'OPEN') ||
      (s.canResume && (s.controlState !== 'PAUSE_REQUESTED' || !drained)) ||
      (s.canRequestCancel &&
        (s.controlState === 'CANCEL_REQUESTED' ||
          !reconciled ||
          w.queued > 200)) ||
      (s.canAcknowledgeCancel &&
        (s.controlState !== 'CANCEL_REQUESTED' ||
          !reconciled ||
          w.queued !== 0 ||
          s.runs.some(
            r => !['CANCELLING', 'COMPLETED', 'CANCELLED'].includes(r.status),
          )))
    );
  }
  private async authorize() {
    if (![this.org, this.team, this.mission].every(uuid) || !this.auth.user())
      throw new Error('Invalid scope');
    const {data} = await firstValueFrom(
      this.http
        .get<{
          data: {id: string; role: string};
        }>(`/api/v1/auth/workspaces/${this.org}`)
        .pipe(timeout(15000)),
    );
    if (data?.id !== this.org || data.role !== 'ADMIN')
      throw new HttpErrorResponse({status: 403});
  }
  private fail(e: unknown) {
    this.data.set(null);
    this.selected.set(null);
    this.confirmed.set(false);
    if (e instanceof HttpErrorResponse && [401, 403].includes(e.status)) {
      this.pending.set(null);
      this.pendingSource = null;
      this.receipt.set(null);
      this.message.set('Quyền ADMIN hiện tại không còn hiệu lực.');
    } else
      this.message.set(
        'Không xác nhận được điều kiện thực thi. Kiểm tra lại trạng thái hiện tại.',
      );
  }
  async load() {
    if (this.busy() || this.pending()) return;
    const g = ++this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    this.data.set(null);
    this.selected.set(null);
    this.confirmed.set(false);
    try {
      await this.authorize();
      if (!this.active(g, a)) return;
      const {data} = await firstValueFrom(
        this.http.get<{data: Snapshot}>(this.base()).pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (!this.valid(data)) throw new Error('Invalid snapshot');
      this.data.set(data);
      this.reviewedAt = Date.now();
      this.message.set('Đã kiểm tra trạng thái thực thi.');
    } catch (e) {
      if (this.active(g, a)) this.fail(e);
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
  }
  choose(action: Action) {
    if (this.busy() || this.pending() || !this.actions().includes(action))
      return;
    this.selected.set(action);
    this.confirmed.set(false);
  }
  apply() {
    const action = this.selected(),
      s = this.data();
    if (
      !action ||
      !s ||
      !this.confirmed() ||
      this.busy() ||
      this.pending() ||
      !this.actions().includes(action)
    )
      return;
    if (Date.now() - this.reviewedAt > 60000) {
      this.data.set(null);
      this.selected.set(null);
      this.confirmed.set(false);
      this.message.set('Bản xem trước đã hết hạn. Kiểm tra lại trạng thái.');
      return;
    }
    this.pendingSource = s;
    this.pending.set({
      action,
      expectedFingerprint: s.fingerprint,
      idempotencyKey: crypto.randomUUID(),
    });
    void this.send();
  }
  async send() {
    const p = this.pending(),
      s = this.pendingSource;
    if (!p || !s || this.busy()) return;
    const g = ++this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    try {
      await this.authorize();
      if (!this.active(g, a)) return;
      const {data: csrf} = await firstValueFrom(
        this.http
          .get<{data: {headerName: string; token: string}}>('/api/v1/auth/csrf')
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (
        csrf?.headerName !== 'X-CSRF-TOKEN' ||
        typeof csrf.token !== 'string' ||
        !csrf.token ||
        csrf.token.length > 4096
      )
        throw new Error('Invalid CSRF');
      const {data: r} = await firstValueFrom(
        this.http
          .post<{
            data: Receipt;
          }>(this.base(), p, {
            headers: new HttpHeaders().set(csrf.headerName, csrf.token),
          })
          .pipe(timeout(20000)),
      );
      if (!this.active(g, a)) return;
      const state =
        p.action === 'PAUSE'
          ? 'PAUSE_REQUESTED'
          : p.action === 'RESUME'
            ? 'OPEN'
            : 'CANCEL_REQUESTED';
      if (
        !this.scope(r) ||
        !hash(r.snapshotSha256) ||
        r.idempotencyKey !== p.idempotencyKey ||
        r.action !== p.action ||
        r.sourceFingerprint !== p.expectedFingerprint ||
        r.controlRevisionBefore !== s.controlRevision ||
        !revision(r.controlRevisionAfter) ||
        BigInt(r.controlRevisionAfter) !==
          BigInt(r.controlRevisionBefore) + 1n ||
        r.controlState !== state ||
        r.missionStatus !==
          (p.action === 'ACK_CANCEL' ? 'CANCELLED' : s.missionStatus) ||
        !Array.isArray(r.retiredQueuedDispatchIds) ||
        r.retiredQueuedDispatchIds.length !==
          (p.action === 'REQUEST_CANCEL' ? s.work.queued : 0) ||
        r.retiredQueuedDispatchIds.some(id => !uuid(id)) ||
        new Set(r.retiredQueuedDispatchIds).size !==
          r.retiredQueuedDispatchIds.length
      )
        throw new Error('Uncertain receipt');
      this.pending.set(null);
      this.pendingSource = null;
      this.receipt.set(r);
      this.data.set(null);
      this.selected.set(null);
      this.confirmed.set(false);
      this.message.set('Lệnh đã được ghi nhận. Kiểm tra trạng thái mới.');
    } catch (e) {
      if (!this.active(g, a)) return;
      if (
        e instanceof HttpErrorResponse &&
        [400, 401, 403, 404, 409].includes(e.status)
      ) {
        this.pending.set(null);
        this.pendingSource = null;
        this.fail(e);
      } else {
        this.data.set(null);
        this.selected.set(null);
        this.confirmed.set(false);
        this.message.set(
          'Kết quả chưa xác định. Gửi lại đúng lệnh đang giữ để đối chiếu.',
        );
      }
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
  }
  @HostListener('window:focus') focus() {
    this.data.set(null);
    this.selected.set(null);
    this.confirmed.set(false);
    if (!this.busy() && !this.pending()) void this.load();
    else if (!this.busy()) void this.authorize().catch(e => this.fail(e));
  }
  @HostListener('document:visibilitychange') visible() {
    if (document.visibilityState === 'visible') this.focus();
  }
}

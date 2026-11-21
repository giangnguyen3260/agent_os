import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  HostListener,
  effect,
  inject,
  signal,
} from '@angular/core';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {firstValueFrom, timeout} from 'rxjs';
import {AuthService} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
interface Task {
  id: string;
  title: string;
  status: string;
  ownerId: string | null;
  ownerName: string | null;
  ownerStatus: string | null;
  editRevision: string;
  ownerRevision: string;
  dependencyRevision: string;
  description: string | null;
  acceptanceCriteria: string | null;
  priority: string | null;
}
interface Review {
  organizationId: string;
  teamId: string;
  missionId: string;
  title: string;
  status: string;
  taskCount: number;
  edgeCount: number;
  tasks: Task[];
  edges: {taskId: string; dependencyId: string}[];
  order: string[];
  blockers: string[];
  canReviewConfiguration: boolean;
  executionAuthorized: false;
  fingerprint: string;
}
interface Accepted {
  organizationId: string;
  teamId: string;
  missionId: string;
  planRevision: string | null;
  matchesCurrent: boolean;
  snapshot: Review | null;
  executionAuthorized: false;
}
interface Command {
  idempotencyKey: string;
  expectedFingerprint: string;
}
interface GraphRun {
  taskId: string;
  runId: string;
  root: boolean;
  taskStatus: string;
  runStatus: string;
}
interface GraphAssessment {
  organizationId: string;
  teamId: string;
  missionId: string;
  status: string;
  planRevision: string | null;
  snapshot: Review | null;
  runs: GraphRun[];
  admitted: boolean;
  canAdmit: boolean;
  blockers: string[];
  fingerprint: string;
  executionAuthorized: false;
}
interface GraphCommand extends Command {
  expectedPlanRevision: string;
}
interface GraphReceipt {
  organizationId: string;
  teamId: string;
  missionId: string;
  planRevision: string;
  idempotencyKey: string;
  sourceFingerprint: string;
  runs: GraphRun[];
  executionAuthorized: false;
}
function revision(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[1-9][0-9]{0,18}$/.test(value) &&
    BigInt(value) <= 9223372036854775807n
  );
}
const reasons: Record<string, string> = {
  MISSION_STATE_UNAVAILABLE: 'Mission cần ở trạng thái CREATED hoặc READY.',
  PARENT_UNAVAILABLE: 'Workspace và Team cần hoạt động.',
  NO_TASKS: 'Mission chưa có Task.',
  SCOPE_LIMIT_EXCEEDED:
    'Phạm vi vượt giới hạn 200 Task hoặc 1.000 quan hệ phụ thuộc; chưa hiển thị kế hoạch đầy đủ.',
  TASK_STATE_UNAVAILABLE: 'Các Task cần ở trạng thái DRAFT.',
  OWNER_UNAVAILABLE: 'Mỗi Task cần Agent đang hoạt động trong cùng Team.',
  TASK_INPUT_UNAVAILABLE: 'Cần bổ sung đầu vào và tiêu chí chấp nhận cho Task.',
  INPUT_LIMIT_EXCEEDED:
    'Nội dung đầu vào vượt giới hạn xem xét; chưa hiển thị kế hoạch đầy đủ.',
  INVALID_DEPENDENCY_GRAPH: 'Quan hệ phụ thuộc không hợp lệ hoặc có chu trình.',
};
@Component({
  standalone: true,
  imports: [RouterLink],
  styleUrl: '../execution-review/execution-review.component.css',
  styles: [
    'article {border-top:1px solid #d4e1da; padding:16px 0;} p,code {overflow-wrap:anywhere;}',
  ],
  template: `<main>
    <h1>Kế hoạch từ Task đã cấu hình</h1>
    <a
      [routerLink]="[
        '/workspace',
        org,
        'teams',
        team,
        'missions',
        mission,
        'workspace',
      ]"
      >Về Mission workspace</a
    >
    <p>
      Xem toàn bộ Task, Agent phụ trách và thứ tự phụ thuộc hiện tại. Kế hoạch
      chỉ xác nhận cấu hình; chưa cho phép thực thi.
    </p>
    <p role="status">{{ busy() ? 'Đang kiểm tra…' : message() }}</p>
    <button (click)="load()" [disabled]="busy()">Tải lại cấu hình</button>
    @if (receipt()) {
      <p role="status">{{ receipt() }}</p>
    }
    @if (pending()) {
      <p>Kết quả gửi chưa xác định. Gửi lại đúng lệnh đang giữ để đối chiếu.</p>
      <button (click)="send()" [disabled]="busy()">Gửi lại lệnh duyệt</button>
    }
    @if (accepted(); as saved) {
      @if (saved.snapshot; as snapshot) {
        <section>
          <h2>Kế hoạch đã duyệt · phiên bản {{ saved.planRevision }}</h2>
          <p>
            {{
              saved.matchesCurrent
                ? 'Khớp cấu hình hiện tại.'
                : 'Cấu hình đã thay đổi hoặc không còn đủ điều kiện; cần xem xét lại.'
            }}
          </p>
          <details>
            <summary>Nội dung đã lưu bất biến</summary>
            @for (t of ordered(snapshot); track t.id) {
              <article>
                <h3>{{ t.title }}</h3>
                <p>Agent: {{ t.ownerName }}</p>
                <p>
                  Phụ thuộc:
                  @for (id of dependencies(snapshot, t.id); track id) {
                    {{ taskName(snapshot, id) }} ·
                  }
                </p>
                <pre>{{ t.description }}</pre>
                <pre>{{ t.acceptanceCriteria }}</pre>
              </article>
            }
          </details>
        </section>
      } @else {
        <p>Chưa có kế hoạch được duyệt.</p>
      }
    }
    @if (graphReceipt()) {
      <p role="status">{{ graphReceipt() }}</p>
    }
    @if (graphPending()) {
      <p>Kết quả tiếp nhận chưa xác định. Giữ nguyên lệnh đang chờ.</p>
      <button (click)="sendGraph()" [disabled]="busy()">
        Gửi lại lệnh tiếp nhận
      </button>
    }
    @if (graph(); as g) {
      <section>
        <h2>Thực thi theo kế hoạch</h2>
        <p>Phiên bản {{ g.planRevision || 'chưa có' }} · {{ g.status }}</p>
        <p>
          Tiếp nhận tạo đủ Run và mở Task theo phụ thuộc. Mỗi Run vẫn cần xem
          trước và Start riêng; ngân sách được kiểm tra khi thực thi.
        </p>
        @if (g.canAdmit && !graphPending() && !pending()) {
          <label
            ><input
              type="checkbox"
              [checked]="graphConfirm()"
              (change)="graphConfirm.set($any($event.target).checked)"
              [disabled]="busy()"
            />
            Tôi xác nhận tiếp nhận toàn bộ kế hoạch đã duyệt này.</label
          >
          <button (click)="admitGraph()" [disabled]="busy() || !graphConfirm()">
            Tiếp nhận toàn bộ kế hoạch
          </button>
        }
        @if (!g.admitted && !g.canAdmit) {
          <p>
            Cần kế hoạch đã duyệt khớp cấu hình READY và chưa có thực thi trước
            đó.
          </p>
        }
        @for (run of g.runs; track run.runId) {
          <article>
            <h3>
              {{ g.snapshot ? taskName(g.snapshot, run.taskId) : run.taskId }}
            </h3>
            <p>
              {{ run.taskStatus }} · Run {{ run.runStatus }} ·
              {{ run.root ? 'Task gốc' : 'Task có phụ thuộc' }}
            </p>
            <a [routerLink]="['/workspace', org, 'execution-runs', run.runId]"
              >Xem Run</a
            >
            @if (run.taskStatus === 'READY' && run.runStatus === 'CREATED') {
              <a
                [routerLink]="[
                  '/workspace',
                  org,
                  'execution-runs',
                  run.runId,
                  'start',
                ]"
                >Xem trước và Start Run</a
              >
            }
          </article>
        }
      </section>
    }
    @if (data(); as r) {
      <section>
        <h2>{{ r.title }}</h2>
        <p>{{ r.taskCount }} Task · {{ r.edgeCount }} quan hệ phụ thuộc</p>
        @if (r.canReviewConfiguration) {
          <p>
            Cấu hình đủ điều kiện để xem xét. Chưa kiểm chứng quyền model, ngân
            sách hoặc điều kiện khởi chạy.
          </p>
        }
        @if (
          enabled() &&
          accepted() &&
          r.canReviewConfiguration &&
          !graphPending() &&
          !accepted()?.matchesCurrent &&
          !pending()
        ) {
          <label
            ><input
              type="checkbox"
              [checked]="confirm()"
              (change)="confirm.set($any($event.target).checked)"
              [disabled]="busy()"
            />
            Tôi xác nhận toàn bộ Task, nội dung và quan hệ phụ thuộc hiển thị
            hiện tại.</label
          >
          <button (click)="accept()" [disabled]="busy() || !confirm()">
            Chấp nhận kế hoạch
          </button>
        }
        @if (r.blockers.length) {
          <ul>
            @for (code of r.blockers; track code) {
              <li>{{ reason(code) }}</li>
            }
          </ul>
        }
        @for (task of ordered(r); track task.id) {
          <article>
            <h3>
              <a
                [routerLink]="[
                  '/workspace',
                  org,
                  'teams',
                  team,
                  'missions',
                  mission,
                  'tasks',
                  task.id,
                ]"
                >{{ task.title }}</a
              >
            </h3>
            <p>
              Agent: {{ task.ownerName || 'Chưa có Agent hợp lệ' }} ·
              {{ task.status }}
            </p>
            <p>
              Phụ thuộc:
              @for (id of dependencies(r, task.id); track id) {
                <span>{{ taskName(r, id) }} · </span>
              } @empty {
                <span>Không có</span>
              }
            </p>
            <details>
              <summary>Đầu vào và tiêu chí chấp nhận</summary>
              <pre>{{ task.description }}</pre>
              <pre>{{ task.acceptanceCriteria }}</pre>
            </details>
          </article>
        }
        <p>
          Mã đối chiếu cấu hình: <code>{{ r.fingerprint }}</code>
        </p>
      </section>
    }
  </main>`,
})
export class MissionPlanReviewComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private generation = 0;
  private account = this.auth.user()?.id;
  org = '';
  team = '';
  mission = '';
  readonly graph = signal<GraphAssessment | null>(null);
  readonly graphConfirm = signal(false);
  readonly graphPending = signal<GraphCommand | null>(null);
  readonly graphReceipt = signal('');
  private expectedGraph: Review | null = null;
  readonly enabled = signal(false);
  readonly accepted = signal<Accepted | null>(null);
  readonly confirm = signal(false);
  readonly pending = signal<Command | null>(null);
  readonly receipt = signal('');
  readonly data = signal<Review | null>(null);
  readonly busy = signal(false);
  readonly message = signal('');
  constructor() {
    effect(() => {
      const a = this.auth.user()?.id;
      if (a !== this.account) {
        this.account = a;
        this.clear();
        this.message.set('Phiên đăng nhập đã thay đổi.');
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(p => {
      this.clear();
      this.org = p.get('id') || '';
      this.team = p.get('teamId') || '';
      this.mission = p.get('missionId') || '';
      void this.load();
    });
    this.destroy.onDestroy(() => this.clear());
  }
  private clear() {
    this.generation++;
    this.graph.set(null);
    this.graphConfirm.set(false);
    this.graphPending.set(null);
    this.graphReceipt.set('');
    this.expectedGraph = null;
    this.enabled.set(false);
    this.accepted.set(null);
    this.confirm.set(false);
    this.pending.set(null);
    this.receipt.set('');
    this.data.set(null);
    this.busy.set(false);
    this.message.set('');
  }
  private active(g: number, a: string | undefined) {
    return (
      !this.destroy.destroyed &&
      g === this.generation &&
      !!a &&
      a === this.auth.user()?.id
    );
  }
  reason(code: string) {
    return reasons[code] || 'Cần kiểm tra lại cấu hình.';
  }
  ordered(r: Review) {
    return r.order.length
      ? r.order.map(id => r.tasks.find(t => t.id === id)!)
      : r.tasks;
  }
  dependencies(r: Review, id: string) {
    return r.edges.filter(e => e.taskId === id).map(e => e.dependencyId);
  }
  taskName(r: Review, id: string) {
    return r.tasks.find(t => t.id === id)?.title || id;
  }
  @HostListener('window:focus') focus() {
    if (!this.busy()) void this.load();
  }
  @HostListener('document:visibilitychange') visible() {
    if (document.visibilityState === 'visible') this.focus();
  }
  private valid(r: Review) {
    if (
      !r ||
      r.organizationId !== this.org ||
      r.teamId !== this.team ||
      r.missionId !== this.mission ||
      r.executionAuthorized !== false ||
      typeof r.title !== 'string' ||
      typeof r.status !== 'string' ||
      typeof r.canReviewConfiguration !== 'boolean' ||
      !Number.isSafeInteger(r.taskCount) ||
      r.taskCount < 0 ||
      !Number.isSafeInteger(r.edgeCount) ||
      r.edgeCount < 0 ||
      !Array.isArray(r.tasks) ||
      r.tasks.length > 200 ||
      !Array.isArray(r.edges) ||
      r.edges.length > 1000 ||
      !Array.isArray(r.order) ||
      !Array.isArray(r.blockers) ||
      r.blockers.length > 20 ||
      new Set(r.blockers).size !== r.blockers.length ||
      !r.blockers.every(c => Object.hasOwn(reasons, c)) ||
      typeof r.fingerprint !== 'string' ||
      !/^[0-9a-f]{64}$/.test(r.fingerprint)
    )
      return false;
    if (
      r.tasks.some(
        t =>
          !t ||
          !uuid(t.id) ||
          typeof t.title !== 'string' ||
          typeof t.status !== 'string' ||
          (t.ownerId !== null && !uuid(t.ownerId)) ||
          ![t.editRevision, t.ownerRevision, t.dependencyRevision].every(
            v => typeof v === 'string' && /^(0|[1-9]\d{0,18})$/.test(v),
          ) ||
          !(
            t.description === null ||
            (typeof t.description === 'string' && t.description.length <= 8000)
          ) ||
          !(
            t.acceptanceCriteria === null ||
            (typeof t.acceptanceCriteria === 'string' &&
              t.acceptanceCriteria.length <= 4000)
          ) ||
          !(t.ownerName === null || typeof t.ownerName === 'string') ||
          !(t.ownerStatus === null || typeof t.ownerStatus === 'string'),
      )
    )
      return false;
    if (
      r.tasks.reduce(
        (sum, t) =>
          sum +
          new TextEncoder().encode(t.description || '').length +
          new TextEncoder().encode(t.acceptanceCriteria || '').length,
        0,
      ) > 1048576 ||
      r.tasks.some(
        t =>
          !(
            t.priority === null ||
            ['LOW', 'NORMAL', 'HIGH', 'URGENT'].includes(t.priority)
          ),
      )
    )
      return false;
    const ids = new Set(r.tasks.map(t => t.id)),
      edgeKeys = r.edges.map(e => `${e?.taskId}:${e?.dependencyId}`);
    if (
      ids.size !== r.tasks.length ||
      r.edges.some(e => !e || !ids.has(e.taskId) || !ids.has(e.dependencyId)) ||
      new Set(edgeKeys).size !== edgeKeys.length
    )
      return false;
    const bounded =
      r.blockers.includes('SCOPE_LIMIT_EXCEEDED') ||
      r.blockers.includes('INPUT_LIMIT_EXCEEDED');
    if (bounded)
      return (
        !r.canReviewConfiguration &&
        !r.tasks.length &&
        !r.edges.length &&
        !r.order.length
      );
    if (r.tasks.length !== r.taskCount || r.edges.length !== r.edgeCount)
      return false;
    if (r.order.length) {
      const positions = new Map(r.order.map((id, i) => [id, i]));
      if (
        positions.size !== ids.size ||
        r.order.length !== ids.size ||
        r.order.some(id => !ids.has(id)) ||
        r.edges.some(
          e => positions.get(e.dependencyId)! >= positions.get(e.taskId)!,
        )
      )
        return false;
    }
    return (
      !r.canReviewConfiguration ||
      (!r.blockers.length &&
        ['CREATED', 'READY'].includes(r.status) &&
        r.tasks.length > 0 &&
        r.order.length === r.tasks.length &&
        r.tasks.every(
          t =>
            t.status === 'DRAFT' &&
            uuid(t.ownerId) &&
            t.ownerStatus === 'ACTIVE' &&
            !!t.description?.trim() &&
            !!t.acceptanceCriteria?.trim() &&
            ['LOW', 'NORMAL', 'HIGH', 'URGENT'].includes(t.priority || ''),
        ))
    );
  }
  async load() {
    if (this.busy()) return;
    const g = ++this.generation,
      a = this.auth.user()?.id;
    this.data.set(null);
    this.accepted.set(null);
    this.graph.set(null);
    this.graphConfirm.set(false);
    this.enabled.set(false);
    this.confirm.set(false);
    this.busy.set(true);
    this.message.set('');
    try {
      if (![this.org, this.team, this.mission].every(uuid))
        throw new Error('Invalid scope');
      const {data: w} = await firstValueFrom(
        this.http
          .get<{
            data: {id: string; role: string};
          }>(`/api/v1/auth/workspaces/${this.org}`)
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (w?.id !== this.org || w.role !== 'ADMIN')
        throw new HttpErrorResponse({status: 403});
      const response = await firstValueFrom(
        this.http
          .get<{
            data: Review;
          }>(
            `/api/v1/organizations/${this.org}/teams/${this.team}/missions/${this.mission}/plan-review`,
            {observe: 'response'},
          )
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      const r = response.body?.data;
      if (!r || !this.valid(r))
        throw new Error('Invalid configuration assessment');
      if (response.headers.get('X-AgentOS-Plan-Acceptance') === 'true') {
        const {data: saved} = await firstValueFrom(
          this.http
            .get<{data: Accepted}>(this.base() + '/accepted-plan')
            .pipe(timeout(15000)),
        );
        if (!this.active(g, a)) return;
        if (
          !saved ||
          saved.organizationId !== this.org ||
          saved.teamId !== this.team ||
          saved.missionId !== this.mission ||
          saved.executionAuthorized !== false ||
          typeof saved.matchesCurrent !== 'boolean' ||
          (saved.planRevision === null
            ? saved.snapshot !== null || saved.matchesCurrent
            : !revision(saved.planRevision) ||
              !saved.snapshot ||
              !this.valid(saved.snapshot) ||
              !saved.snapshot.canReviewConfiguration)
        )
          throw new Error('Invalid accepted snapshot');
        this.accepted.set(saved);
        this.enabled.set(true);
      }
      if (response.headers.get('X-AgentOS-Graph-Admission') === 'true') {
        const {data: graph} = await firstValueFrom(
          this.http
            .get<{data: GraphAssessment}>(this.base() + '/graph-admission')
            .pipe(timeout(15000)),
        );
        if (!this.active(g, a)) return;
        if (!this.validGraph(graph))
          throw new Error('Invalid graph admission assessment');
        this.graph.set(graph);
      }
      this.data.set(r);
    } catch (e) {
      if (this.active(g, a)) {
        if (e instanceof HttpErrorResponse && [401, 403].includes(e.status))
          this.clear();
        this.message.set(
          e instanceof HttpErrorResponse && [401, 403].includes(e.status)
            ? 'Cần quyền ADMIN hiện hành.'
            : e instanceof HttpErrorResponse && e.status === 404
              ? 'Mission hoặc tính năng xem kế hoạch chưa khả dụng.'
              : 'Không xác nhận được toàn bộ cấu hình. Hãy tải lại.',
        );
      }
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
  }
  private base() {
    return `/api/v1/organizations/${this.org}/teams/${this.team}/missions/${this.mission}`;
  }
  accept() {
    const r = this.data();
    if (
      !r?.canReviewConfiguration ||
      !this.enabled() ||
      !this.accepted() ||
      this.accepted()?.matchesCurrent ||
      !this.confirm() ||
      this.graphPending() ||
      this.busy() ||
      this.pending()
    )
      return;
    this.pending.set({
      idempotencyKey: crypto.randomUUID(),
      expectedFingerprint: r.fingerprint,
    });
    void this.send();
  }
  async send() {
    const p = this.pending();
    if (!p || this.busy()) return;
    const g = this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    this.message.set('');
    this.confirm.set(false);
    let success = false;
    try {
      const {data: w} = await firstValueFrom(
        this.http
          .get<{
            data: {id: string; role: string};
          }>(`/api/v1/auth/workspaces/${this.org}`)
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (w?.id !== this.org || w.role !== 'ADMIN')
        throw new HttpErrorResponse({status: 403});
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
      const {data: result} = await firstValueFrom(
        this.http
          .post<{
            data: {
              organizationId: string;
              teamId: string;
              missionId: string;
              planRevision: string;
              idempotencyKey: string;
              sourceFingerprint: string;
              executionAuthorized: false;
            };
          }>(this.base() + '/plan-review/accept', p, {
            headers: new HttpHeaders().set(csrf.headerName, csrf.token),
          })
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (
        !result ||
        result.organizationId !== this.org ||
        result.teamId !== this.team ||
        result.missionId !== this.mission ||
        result.idempotencyKey !== p.idempotencyKey ||
        result.sourceFingerprint !== p.expectedFingerprint ||
        result.executionAuthorized !== false ||
        !revision(result.planRevision)
      )
        throw new Error('Unconfirmed receipt');
      this.pending.set(null);
      this.receipt.set(
        `Đã ghi nhận lệnh duyệt phiên bản ${result.planRevision}. Cấu hình hiện tại được kiểm tra riêng bên dưới.`,
      );
      success = true;
    } catch (e) {
      if (!this.active(g, a)) return;
      this.data.set(null);
      this.accepted.set(null);
      this.enabled.set(false);
      if (
        e instanceof HttpErrorResponse &&
        [400, 401, 403, 404, 409].includes(e.status)
      ) {
        this.pending.set(null);
        if ([401, 403].includes(e.status)) this.clear();
        this.message.set(
          [401, 403].includes(e.status)
            ? 'Cần quyền ADMIN hiện hành.'
            : 'Chưa duyệt được kế hoạch. Tải lại cấu hình để kiểm tra.',
        );
      } else
        this.message.set(
          'Kết quả gửi chưa xác định. Giữ đúng lệnh để gửi lại.',
        );
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
    if (success && this.active(g, a)) await this.load();
  }
  private validRuns(runs: GraphRun[], snapshot: Review, original = false) {
    if (!Array.isArray(runs) || runs.length !== snapshot.tasks.length)
      return false;
    const tasks = new Set<string>(),
      ids = new Set<string>();
    return runs.every(run => {
      const root = !snapshot.edges.some(e => e.taskId === run.taskId);
      if (
        !uuid(run.runId) ||
        !uuid(run.taskId) ||
        tasks.has(run.taskId) ||
        ids.has(run.runId) ||
        !snapshot.tasks.some(t => t.id === run.taskId) ||
        run.root !== root ||
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
        ].includes(run.taskStatus) ||
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
        ].includes(run.runStatus) ||
        (original &&
          (run.taskStatus !== (root ? 'READY' : 'BLOCKED') ||
            run.runStatus !== (root ? 'CREATED' : 'BLOCKED')))
      )
        return false;
      tasks.add(run.taskId);
      ids.add(run.runId);
      return true;
    });
  }
  private validGraph(g: GraphAssessment) {
    return (
      !!g &&
      g.organizationId === this.org &&
      g.teamId === this.team &&
      g.missionId === this.mission &&
      g.executionAuthorized === false &&
      typeof g.admitted === 'boolean' &&
      typeof g.canAdmit === 'boolean' &&
      typeof g.status === 'string' &&
      Array.isArray(g.blockers) &&
      g.blockers.every(b => typeof b === 'string') &&
      /^[0-9a-f]{64}$/.test(g.fingerprint) &&
      Array.isArray(g.runs) &&
      (g.planRevision === null
        ? g.snapshot === null && !g.canAdmit && !g.admitted
        : revision(g.planRevision) &&
          !!g.snapshot &&
          this.valid(g.snapshot) &&
          g.snapshot.canReviewConfiguration) &&
      (g.admitted
        ? !g.canAdmit && !!g.snapshot && this.validRuns(g.runs, g.snapshot)
        : g.runs.length === 0) &&
      (!g.canAdmit ||
        (!g.admitted &&
          g.status === 'READY' &&
          g.blockers.length === 0 &&
          !!g.snapshot))
    );
  }
  admitGraph() {
    const g = this.graph();
    if (
      !g?.canAdmit ||
      !g.snapshot ||
      !g.planRevision ||
      !this.graphConfirm() ||
      this.busy() ||
      this.pending() ||
      this.graphPending()
    )
      return;
    this.expectedGraph = g.snapshot;
    this.graphPending.set({
      idempotencyKey: crypto.randomUUID(),
      expectedPlanRevision: g.planRevision,
      expectedFingerprint: g.fingerprint,
    });
    void this.sendGraph();
  }
  async sendGraph() {
    const command = this.graphPending();
    if (!command || this.busy()) return;
    const g = this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    this.graphConfirm.set(false);
    this.message.set('');
    let success = false;
    try {
      const {data: w} = await firstValueFrom(
        this.http
          .get<{
            data: {id: string; role: string};
          }>(`/api/v1/auth/workspaces/${this.org}`)
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (w?.id !== this.org || w.role !== 'ADMIN')
        throw new HttpErrorResponse({status: 403});
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
            data: GraphReceipt;
          }>(this.base() + '/graph-admission', command, {
            headers: new HttpHeaders().set(csrf.headerName, csrf.token),
          })
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (
        !r ||
        r.organizationId !== this.org ||
        r.teamId !== this.team ||
        r.missionId !== this.mission ||
        r.executionAuthorized !== false ||
        r.idempotencyKey !== command.idempotencyKey ||
        r.sourceFingerprint !== command.expectedFingerprint ||
        r.planRevision !== command.expectedPlanRevision ||
        !revision(r.planRevision) ||
        !this.expectedGraph ||
        !this.validRuns(r.runs, this.expectedGraph, true)
      )
        throw new Error('Unconfirmed graph receipt');
      this.graphPending.set(null);
      this.expectedGraph = null;
      this.graphReceipt.set(
        `Đã ghi nhận tiếp nhận phiên bản ${r.planRevision}, gồm ${r.runs.length} Run. Trạng thái hiện tại được kiểm tra riêng.`,
      );
      success = true;
    } catch (e) {
      if (!this.active(g, a)) return;
      this.data.set(null);
      this.accepted.set(null);
      this.graph.set(null);
      this.enabled.set(false);
      if (
        e instanceof HttpErrorResponse &&
        [400, 401, 403, 404, 409].includes(e.status)
      ) {
        this.graphPending.set(null);
        this.expectedGraph = null;
        if ([401, 403].includes(e.status)) this.clear();
        this.message.set(
          [401, 403].includes(e.status)
            ? 'Cần quyền ADMIN hiện hành.'
            : 'Chưa tiếp nhận được kế hoạch. Tải lại để đối chiếu cấu hình.',
        );
      } else
        this.message.set(
          'Kết quả tiếp nhận chưa xác định. Giữ đúng lệnh để gửi lại.',
        );
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
    if (success && this.active(g, a)) await this.load();
  }
}

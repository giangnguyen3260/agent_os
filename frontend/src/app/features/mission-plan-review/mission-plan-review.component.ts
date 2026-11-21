import {HttpClient, HttpErrorResponse} from '@angular/common/http';
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
const reasons: Record<string, string> = {
  MISSION_STATE_UNAVAILABLE: 'Mission cần ở trạng thái CREATED.',
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
      này chưa được chấp nhận và chưa cho phép thực thi.
    </p>
    <p role="status">{{ busy() ? 'Đang kiểm tra…' : message() }}</p>
    <button (click)="load()" [disabled]="busy()">Tải lại cấu hình</button>
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
        r.status === 'CREATED' &&
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
      const {data: r} = await firstValueFrom(
        this.http
          .get<{
            data: Review;
          }>(
            `/api/v1/organizations/${this.org}/teams/${this.team}/missions/${this.mission}/plan-review`,
          )
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (!this.valid(r)) throw new Error('Invalid configuration assessment');
      this.data.set(r);
    } catch (e) {
      if (this.active(g, a))
        this.message.set(
          e instanceof HttpErrorResponse && [401, 403].includes(e.status)
            ? 'Cần quyền ADMIN hiện hành.'
            : e instanceof HttpErrorResponse && e.status === 404
              ? 'Mission hoặc tính năng xem kế hoạch chưa khả dụng.'
              : 'Không xác nhận được toàn bộ cấu hình. Hãy tải lại.',
        );
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
  }
}

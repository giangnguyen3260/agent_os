import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {Component, HostListener, effect, inject, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom, timeout} from 'rxjs';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {AuthService} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
interface Owner {
  id: string;
  name: string;
}
interface Context {
  organizationId: string;
  teamId: string;
  missionId: string;
  title: string;
  intakeVersion: string;
  owners: Owner[];
}
interface Task {
  key: string;
  title: string;
  description: string;
  acceptanceCriteria: string;
  priority: string;
  dependencies: string[];
}
interface Preview {
  organizationId: string;
  teamId: string;
  missionId: string;
  sourceRunId: string;
  sourceMissionId: string;
  sourceAttemptId: string;
  sourceOutputSha256: string;
  sourceProvenanceSha256: string;
  sourceCompletionFingerprint: string;
  sourceJson: string;
  proposalJson: string;
  origin: string;
  proposal: {schemaVersion: number; tasks: Task[]; order: string[]};
  owners: Record<string, string>;
  intakeVersion: string;
  fingerprint: string;
  executionAuthorized: false;
}
interface Command {
  sourceRunId: string;
  proposalJson: string;
  owners: Record<string, string>;
  idempotencyKey: string;
  expectedFingerprint: string;
}
interface Receipt {
  organizationId: string;
  teamId: string;
  missionId: string;
  idempotencyKey: string;
  sourceRunId: string;
  reviewedFingerprint: string;
  snapshotSha256: string;
  origin: string;
  intakeVersionBefore: string;
  intakeVersionAfter: string;
  taskIds: Record<string, string>;
  executionAuthorized: false;
}
@Component({
  standalone: true,
  imports: [FormsModule, RouterLink],
  styleUrl: '../execution-review/execution-review.component.css',
  styles: [
    'textarea {width:100%;min-height:220px;} pre {white-space:pre-wrap;overflow-wrap:anywhere;} select,input {max-width:100%;} article {border-top:1px solid #cad8cf;padding:12px 0;}',
  ],
  template: `<main>
    <h1>Đề xuất kế hoạch từ Agent</h1>
    <a [routerLink]="workspace()">Về Mission workspace</a>
    <p>
      Lấy output đã ACCEPT từ Mission lập kế hoạch đã đóng trong cùng Team. Chọn
      Agent phụ trách, xem lại toàn bộ Task trước khi nhập vào Mission trống.
    </p>
    <p>
      Áp dụng chỉ tạo Task DRAFT. Sau đó bạn vẫn cần duyệt kế hoạch, tiếp nhận
      Run và Start riêng.
    </p>
    <details>
      <summary>Chuẩn bị đầu ra cho Task lập kế hoạch</summary>
      <p>
        Tạo Task lập kế hoạch với mục tiêu và kết quả cần bàn giao của Mission.
        Yêu cầu Agent chỉ trả JSON thuần theo mẫu, không có Markdown hoặc lời
        dẫn.
      </p>
      <p>
        Dùng 1–20 Task, tối đa 100 quan hệ phụ thuộc, không có chu trình. Mỗi
        key bắt đầu bằng chữ thường và chỉ chứa chữ thường, số hoặc dấu gạch
        dưới, tối đa 40 ký tự. Priority: LOW, NORMAL, HIGH hoặc URGENT.
      </p>
      <p>
        Title tối đa 255, description 8000 và acceptanceCriteria 4000 ký tự; nội
        dung bắt buộc phải có và không thừa khoảng trắng ở đầu/cuối. Giới hạn
        JSON là 64 KiB, không cắt ngắn dữ liệu để nhập.
      </p>
      <label
        >Mẫu JSON đầu ra<textarea readonly [value]="plannerTemplate"></textarea>
      </label>
      <p>
        Đây là mẫu để tham khảo, không phải đề xuất đã được duyệt. Sau khi Task
        nguồn được ACCEPT và Mission nguồn đã đóng, nhập Run nguồn trong cùng
        Team, chỉnh nội dung nếu cần, chọn Agent phụ trách và duyệt toàn bộ
        trước khi áp dụng. Mẫu không tự ghi đè nội dung đang nhập.
      </p>
    </details>
    <p role="status">{{ busy() ? 'Đang kiểm tra…' : message() }}</p>
    @if (receipt(); as saved) {
      <h2>Đã nhập {{ entries(saved.taskIds).length }} Task</h2>
      <p>
        {{
          saved.origin === 'AGENT_OUTPUT'
            ? 'Giữ nguyên nội dung Agent'
            : 'Nội dung đã được người dùng chỉnh sửa'
        }}
        · Snapshot {{ saved.snapshotSha256 }}
      </p>
      @for (item of entries(saved.taskIds); track item[0]) {
        <p>
          <a [routerLink]="[...workspace().slice(0,-1),'tasks',item[1]]">{{
            item[0]
          }}</a>
        </p>
      }
      <a [routerLink]="[...workspace().slice(0,-1),'plan-review']"
        >Xem và duyệt kế hoạch từ Task</a
      >
    } @else {
      @if (pending()) {
        <p>
          Kết quả áp dụng chưa xác định. Giữ nguyên lệnh để đối chiếu; không tạo
          lệnh mới.
        </p>
        <button (click)="send()" [disabled]="busy()">
          Gửi lại đúng lệnh áp dụng
        </button>
      }
      <button (click)="load()" [disabled]="busy() || !!pending()">
        Tải lại điều kiện nhập
      </button>
      @if (context(); as current) {
        <h2>{{ current.title }}</h2>
        <fieldset [disabled]="busy() || !!pending()">
          <label
            >Run lập kế hoạch nguồn
            <input
              aria-label="Run lập kế hoạch nguồn"
              [ngModel]="sourceRun"
              (ngModelChange)="changeSource($event)"
          /></label>
          <button (click)="readSource()" [disabled]="!validSource()">
            Đọc output nguồn đã ACCEPT
          </button>
          @if (sourceText()) {
            <details>
              <summary>Output nguồn nguyên bản</summary>
              <pre>{{ sourceText() }}</pre>
            </details>
          }
          <label
            >JSON đề xuất (có thể chỉnh sửa)<textarea
              aria-label="JSON đề xuất"
              [ngModel]="proposalText"
              (ngModelChange)="edit($event)"
            ></textarea>
          </label>
          <button
            (click)="restoreSource()"
            [disabled]="
              busy() ||
              !!pending() ||
              !sourceText() ||
              proposalText === sourceText()
            "
          >
            Khôi phục JSON nguồn đã ACCEPT
          </button>
          @if (proposalIssue(); as issue) {
            <p role="alert">{{ issue }}</p>
          }
          <p>
            {{
              sourceText() && proposalText === sourceText()
                ? 'AGENT_OUTPUT — giữ nguyên nguồn'
                : 'HUMAN_CORRECTED — bản do người dùng chỉnh sửa'
            }}
          </p>
          <p>
            Schema: schemaVersion = 1, tasks gồm key, title, description,
            acceptanceCriteria, priority (LOW/NORMAL/HIGH/URGENT), dependencies
            (danh sách key). Tối đa 20 Task và 100 quan hệ; không đặt owner hoặc
            lệnh thực thi trong JSON.
          </p>
          @if (rows().length > 0 && !proposalIssue()) {
            <label
              >Agent dùng cho toàn bộ Task
              <select
                aria-label="Agent dùng cho toàn bộ Task"
                [(ngModel)]="bulkOwner"
              >
                <option value="">Chọn Agent</option>
                @for (owner of current.owners; track owner.id) {
                  <option [value]="owner.id">{{ owner.name }}</option>
                }
              </select>
            </label>
            <button (click)="bindAll()" [disabled]="!bulkOwner">
              Gán Agent đã chọn cho toàn bộ Task
            </button>
            <p>
              Thao tác thay các lựa chọn Agent hiện có. Bạn có thể đổi từng Task
              và phải xem trước lại trước khi áp dụng.
            </p>
          }
          @for (task of rows(); track task.key) {
            <label
              >{{ task.key }} — {{ task.title }}
              <select
                [attr.aria-label]="'Agent cho ' + task.key"
                [ngModel]="owners[task.key] || ''"
                (ngModelChange)="bind(task.key, $event)"
              >
                <option value="">Chọn Agent</option>
                @for (owner of current.owners; track owner.id) {
                  <option [value]="owner.id">{{ owner.name }}</option>
                }
              </select>
            </label>
          }
          <button (click)="review()" [disabled]="!canReview()">
            Xem trước toàn bộ kế hoạch
          </button>
        </fieldset>
      }
      @if (preview(); as plan) {
        <section>
          <h2>Kế hoạch sẽ nhập</h2>
          <p>{{ plan.origin }} · Output {{ plan.sourceOutputSha256 }}</p>
          <p>Thứ tự phụ thuộc: {{ plan.proposal.order.join(' → ') }}</p>
          @for (task of plan.proposal.tasks; track task.key) {
            <article>
              <h3>{{ task.key }} — {{ task.title }}</h3>
              <p>{{ task.description }}</p>
              <p>Chấp nhận: {{ task.acceptanceCriteria }}</p>
              <p>
                Ưu tiên: {{ task.priority }} · Agent:
                {{ ownerName(plan.owners[task.key]) }}
              </p>
              <p>Phụ thuộc: {{ task.dependencies.join(', ') || 'Không có' }}</p>
            </article>
          }
          <button (click)="apply()" [disabled]="busy() || !!pending()">
            Áp dụng thành Task DRAFT
          </button>
        </section>
      }
    }
  </main>`,
})
export class MissionPlanProposalComponent {
  readonly plannerTemplate = JSON.stringify(
    {
      schemaVersion: 1,
      tasks: [
        {
          key: 'research',
          title: 'Collect references',
          description: 'Collect relevant evidence for the supplied goal.',
          acceptanceCriteria: 'Relevant references with stated limitations.',
          priority: 'NORMAL',
          dependencies: [],
        },
        {
          key: 'write',
          title: 'Write the deliverable',
          description: 'Use accepted research to produce the required output.',
          acceptanceCriteria:
            'Meets the Mission goal and states remaining gaps.',
          priority: 'NORMAL',
          dependencies: ['research'],
        },
      ],
    },
    null,
    2,
  );
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  org = '';
  team = '';
  mission = '';
  sourceRun = '';
  proposalText = '';
  owners: Record<string, string> = {};
  readonly busy = signal(false);
  readonly message = signal('');
  readonly context = signal<Context | null>(null);
  readonly sourceText = signal('');
  readonly preview = signal<Preview | null>(null);
  readonly pending = signal<Command | null>(null);
  readonly receipt = signal<Receipt | null>(null);
  private epoch = 0;
  private reviewedAt = 0;
  private pendingReview: Preview | null = null;
  private uncertainApply = false;
  private actor: string | null = null;
  constructor() {
    this.actor = this.auth.user()?.id ?? null;
    effect(() => {
      const id = this.auth.user()?.id ?? null;
      if (id !== this.actor) {
        this.actor = id;
        this.reset();
        if (id) void this.load();
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(p => {
      this.org = p.get('id') ?? '';
      this.team = p.get('teamId') ?? '';
      this.mission = p.get('missionId') ?? '';
      this.reset();
      void this.load();
    });
  }
  private reset() {
    this.epoch++;
    this.busy.set(false);
    this.context.set(null);
    this.preview.set(null);
    this.pending.set(null);
    this.pendingReview = null;
    this.uncertainApply = false;
    this.receipt.set(null);
    this.sourceText.set('');
    this.sourceRun = '';
    this.proposalText = '';
    this.owners = {};
    this.bulkOwner = '';
  }
  workspace() {
    return [
      '/workspace',
      this.org,
      'teams',
      this.team,
      'missions',
      this.mission,
      'workspace',
    ];
  }
  entries(value: Record<string, string>) {
    return Object.entries(value);
  }
  ownerName(id: string) {
    return this.context()?.owners.find(o => o.id === id)?.name ?? id;
  }
  validSource() {
    return uuid(this.sourceRun);
  }
  changeSource(value: string) {
    this.sourceRun = value;
    this.sourceText.set('');
    this.proposalText = '';
    this.owners = {};
    this.bulkOwner = '';
    this.preview.set(null);
  }
  edit(value: string) {
    this.proposalText = value;
    this.preview.set(null);
    this.owners = {};
    this.bulkOwner = '';
  }
  bulkOwner = '';
  bindAll() {
    const rows = this.rows();
    if (
      this.busy() ||
      this.pending() ||
      this.proposalIssue() ||
      !rows.length ||
      !this.context()?.owners.some(o => o.id === this.bulkOwner)
    )
      return;
    this.owners = Object.fromEntries(rows.map(t => [t.key, this.bulkOwner]));
    this.preview.set(null);
  }
  restoreSource() {
    const source = this.sourceText();
    if (
      this.busy() ||
      this.pending() ||
      !source ||
      this.proposalText === source
    )
      return;
    if (
      !window.confirm(
        'Thay bản chỉnh sửa bằng output nguồn đã ACCEPT? Bản xem trước và lựa chọn Agent sẽ bị xóa; bạn cần duyệt lại.',
      )
    )
      return;
    this.edit(source);
    this.message.set(
      'Đã khôi phục output nguồn. Chọn lại Agent và xem trước trước khi áp dụng.',
    );
  }
  bind(key: string, value: string) {
    this.owners = {...this.owners, [key]: value};
    this.preview.set(null);
  }
  rows(): Task[] {
    try {
      const parsed = JSON.parse(this.proposalText);
      if (!Array.isArray(parsed.tasks) || parsed.tasks.length > 20) return [];
      const tasks = parsed.tasks as Task[];
      if (
        tasks.some(
          t =>
            !t ||
            typeof t.key !== 'string' ||
            !/^[a-z][a-z0-9_]{0,39}$/.test(t.key) ||
            typeof t.title !== 'string',
        ) ||
        new Set(tasks.map(t => t.key)).size !== tasks.length
      )
        return [];
      return tasks;
    } catch {
      return [];
    }
  }
  proposalIssue(): string {
    if (!this.proposalText.trim()) return '';
    if (new TextEncoder().encode(this.proposalText).length > 65536)
      return 'JSON vượt giới hạn 64 KiB. Rút gọn nội dung, không cắt bỏ Task hoặc phụ thuộc chưa duyệt.';
    let value: unknown;
    try {
      value = JSON.parse(this.proposalText);
    } catch {
      return 'JSON chưa hợp lệ. Kiểm tra dấu ngoặc, dấu phẩy và dấu nháy kép; bỏ Markdown hoặc lời dẫn.';
    }
    if (!value || typeof value !== 'object' || Array.isArray(value))
      return 'Đề xuất phải là một object JSON có schemaVersion và tasks.';
    const proposal = value as {schemaVersion?: unknown; tasks?: unknown};
    if (proposal.schemaVersion !== 1) return 'schemaVersion phải là số 1.';
    if (
      !Array.isArray(proposal.tasks) ||
      proposal.tasks.length < 1 ||
      proposal.tasks.length > 20
    )
      return 'tasks phải chứa từ 1 đến 20 Task.';
    if (this.rows().length !== proposal.tasks.length)
      return 'Mỗi Task cần title dạng chuỗi và key riêng: chữ thường, số hoặc dấu gạch dưới; bắt đầu bằng chữ thường, tối đa 40 ký tự.';
    return '';
  }
  canReview() {
    const rows = this.rows();
    return (
      this.validSource() &&
      !this.proposalIssue() &&
      rows.length > 0 &&
      rows.every(t => uuid(this.owners[t.key] ?? ''))
    );
  }
  private base() {
    return `/api/v1/organizations/${this.org}/teams/${this.team}/missions/${this.mission}/plan-proposal`;
  }
  private scope(value: {
    organizationId: string;
    teamId: string;
    missionId: string;
  }) {
    return (
      value?.organizationId === this.org &&
      value.teamId === this.team &&
      value.missionId === this.mission
    );
  }
  private async admin() {
    if (![this.org, this.team, this.mission].every(uuid) || !this.auth.user())
      throw new Error('Identity unavailable');
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
  private async post<T>(suffix: string, body: unknown, generation: number) {
    const {data} = await firstValueFrom(
      this.http
        .get<{data: {headerName: string; token: string}}>('/api/v1/auth/csrf')
        .pipe(timeout(15000)),
    );
    if (
      data?.headerName !== 'X-CSRF-TOKEN' ||
      typeof data.token !== 'string' ||
      !data.token ||
      data.token.length > 4096
    )
      throw new Error('Invalid CSRF');
    if (generation !== this.epoch) throw new Error('Identity changed');
    return firstValueFrom(
      this.http
        .post<{
          data: T;
        }>(this.base() + suffix, body, {
          headers: new HttpHeaders().set(data.headerName, data.token),
        })
        .pipe(timeout(20000)),
    );
  }
  private fail(error: unknown) {
    this.preview.set(null);
    if (
      error instanceof HttpErrorResponse &&
      [401, 403].includes(error.status)
    ) {
      this.context.set(null);
      this.sourceText.set('');
      this.proposalText = '';
      this.owners = {};
      this.bulkOwner = '';
      this.pending.set(null);
      this.message.set('Quyền ADMIN hiện tại không còn hiệu lực.');
    } else
      this.message.set(
        'Không thể xác nhận đề xuất. Kiểm tra JSON, Agent và điều kiện Mission rồi tải lại.',
      );
  }
  async load() {
    if (this.busy() || this.pending()) return;
    const generation = ++this.epoch;
    this.preview.set(null);
    this.busy.set(true);
    try {
      await this.admin();
      const {data} = await firstValueFrom(
        this.http.get<{data: Context}>(this.base()).pipe(timeout(15000)),
      );
      if (generation !== this.epoch) return;
      if (
        !this.scope(data) ||
        !Array.isArray(data.owners) ||
        data.owners.length > 200 ||
        data.owners.some(o => !uuid(o.id) || typeof o.name !== 'string') ||
        !/^(0|[1-9][0-9]{0,18})$/.test(data.intakeVersion)
      )
        throw new Error('Invalid context');
      this.context.set(data);
      this.message.set('Mission trống, có thể xem xét đề xuất.');
    } catch (e) {
      if (generation === this.epoch) {
        this.context.set(null);
        this.fail(e);
      }
    } finally {
      if (generation === this.epoch) this.busy.set(false);
    }
  }
  async readSource() {
    if (this.busy() || this.pending() || !this.validSource()) return;
    const generation = ++this.epoch;
    this.busy.set(true);
    this.preview.set(null);
    try {
      await this.admin();
      const {data} = await this.post<{runId: string; content: string}>(
        '/source',
        {sourceRunId: this.sourceRun},
        generation,
      );
      if (generation !== this.epoch) return;
      if (
        data?.runId !== this.sourceRun ||
        typeof data.content !== 'string' ||
        new TextEncoder().encode(data.content).length > 65536
      )
        throw new Error('Invalid source');
      this.sourceText.set(data.content);
      this.proposalText = data.content;
      this.owners = {};
      this.bulkOwner = '';
      this.message.set('Đã đọc nguồn. Chọn Agent và xem trước kế hoạch.');
    } catch (e) {
      if (generation === this.epoch) this.fail(e);
    } finally {
      if (generation === this.epoch) this.busy.set(false);
    }
  }
  async review() {
    if (this.busy() || this.pending() || !this.canReview()) return;
    const generation = ++this.epoch;
    this.busy.set(true);
    this.preview.set(null);
    const input = {
      sourceRunId: this.sourceRun,
      proposalJson: this.proposalText,
      owners: {...this.owners},
    };
    try {
      await this.admin();
      const {data} = await this.post<Preview>('/preview', input, generation);
      if (generation !== this.epoch) return;
      if (
        !this.scope(data) ||
        data.executionAuthorized !== false ||
        data.sourceRunId !== input.sourceRunId ||
        data.proposalJson !== input.proposalJson ||
        !/^[0-9a-f]{64}$/.test(data.fingerprint) ||
        !/^[0-9a-f]{64}$/.test(data.sourceOutputSha256) ||
        !/^[0-9a-f]{64}$/.test(data.sourceProvenanceSha256) ||
        !/^[0-9a-f]{64}$/.test(data.sourceCompletionFingerprint) ||
        !uuid(data.sourceMissionId) ||
        !uuid(data.sourceAttemptId) ||
        !['AGENT_OUTPUT', 'HUMAN_CORRECTED'].includes(data.origin) ||
        data.origin !==
          (data.proposalJson === data.sourceJson
            ? 'AGENT_OUTPUT'
            : 'HUMAN_CORRECTED') ||
        !this.matchesProposal(data, input)
      )
        throw new Error('Invalid preview');
      this.sourceText.set(data.sourceJson);
      this.preview.set(data);
      this.reviewedAt = Date.now();
      this.message.set('Xem đủ Task, tiêu chí và quan hệ trước khi áp dụng.');
    } catch (e) {
      if (generation === this.epoch) this.fail(e);
    } finally {
      if (generation === this.epoch) this.busy.set(false);
    }
  }
  private matchesProposal(
    data: Preview,
    input: {proposalJson: string; owners: Record<string, string>},
  ) {
    const parsed = JSON.parse(input.proposalJson);
    if (
      data.proposal?.schemaVersion !== 1 ||
      parsed.schemaVersion !== 1 ||
      !Array.isArray(data.proposal.tasks) ||
      data.proposal.tasks.length < 1 ||
      data.proposal.tasks.length > 20 ||
      !Array.isArray(data.proposal.order) ||
      data.proposal.order.length !== data.proposal.tasks.length
    )
      return false;
    const expected = new Map((parsed.tasks as Task[]).map(t => [t.key, t]));
    if (
      expected.size !== data.proposal.tasks.length ||
      new Set(data.proposal.order).size !== expected.size ||
      data.proposal.order.some(k => !expected.has(k)) ||
      Object.keys(data.owners).sort().join(',') !==
        [...expected.keys()].sort().join(',')
    )
      return false;
    const returnedKeys = data.proposal.tasks.map(t => t.key);
    if (
      new Set(returnedKeys).size !== expected.size ||
      returnedKeys.some(k => !expected.has(k))
    )
      return false;
    for (const task of data.proposal.tasks) {
      if (
        !Array.isArray(task.dependencies) ||
        task.dependencies.some(
          dep =>
            data.proposal.order.indexOf(dep) < 0 ||
            data.proposal.order.indexOf(dep) >=
              data.proposal.order.indexOf(task.key),
        )
      )
        return false;
    }
    return data.proposal.tasks.every(t => {
      const original = expected.get(t.key);
      return (
        original &&
        ['title', 'description', 'acceptanceCriteria', 'priority'].every(
          field =>
            typeof t[field as keyof Task] === 'string' &&
            t[field as keyof Task] === original[field as keyof Task],
        ) &&
        Array.isArray(t.dependencies) &&
        [...t.dependencies].sort().join(',') ===
          [...original.dependencies].sort().join(',') &&
        data.owners[t.key] === input.owners[t.key]
      );
    });
  }
  apply() {
    const plan = this.preview();
    if (!plan || this.busy() || this.pending()) return;
    if (Date.now() - this.reviewedAt > 60000) {
      this.preview.set(null);
      this.message.set(
        'Bản xem trước đã hết hạn. Xem trước lại trước khi áp dụng.',
      );
      return;
    }
    this.pendingReview = plan;
    this.uncertainApply = false;
    this.pending.set({
      sourceRunId: plan.sourceRunId,
      proposalJson: plan.proposalJson,
      owners: {...plan.owners},
      idempotencyKey: crypto.randomUUID(),
      expectedFingerprint: plan.fingerprint,
    });
    void this.send();
  }
  async send() {
    const command = this.pending();
    if (!command || this.busy()) return;
    const generation = ++this.epoch;
    const wasUncertain = this.uncertainApply;
    this.busy.set(true);
    try {
      await this.admin();
      const {data} = await this.post<Receipt>('/apply', command, generation);
      if (generation !== this.epoch) return;
      if (
        !this.scope(data) ||
        data.executionAuthorized !== false ||
        data.idempotencyKey !== command.idempotencyKey ||
        data.sourceRunId !== command.sourceRunId ||
        data.reviewedFingerprint !== command.expectedFingerprint ||
        !/^[0-9a-f]{64}$/.test(data.snapshotSha256) ||
        !['AGENT_OUTPUT', 'HUMAN_CORRECTED'].includes(data.origin) ||
        data.origin !== this.pendingReview?.origin ||
        data.intakeVersionBefore !== this.pendingReview?.intakeVersion ||
        !/^(0|[1-9][0-9]{0,18})$/.test(data.intakeVersionBefore) ||
        !/^[1-9][0-9]{0,18}$/.test(data.intakeVersionAfter) ||
        BigInt(data.intakeVersionAfter) !==
          BigInt(data.intakeVersionBefore) + 1n ||
        !data.taskIds ||
        Object.keys(data.taskIds).sort().join(',') !==
          Object.keys(command.owners).sort().join(',') ||
        Object.values(data.taskIds).some(id => !uuid(id)) ||
        new Set(Object.values(data.taskIds)).size !==
          Object.keys(command.owners).length
      )
        throw new Error('Uncertain receipt');
      this.receipt.set(data);
      this.uncertainApply = false;
      this.pending.set(null);
      this.preview.set(null);
      this.message.set(
        'Đã tạo toàn bộ Task DRAFT. Chưa duyệt kế hoạch hoặc thực thi.',
      );
    } catch (e) {
      if (generation !== this.epoch) return;
      if (
        e instanceof HttpErrorResponse &&
        ([401, 403].includes(e.status) ||
          (!wasUncertain && [400, 404, 409].includes(e.status)))
      ) {
        this.pending.set(null);
        this.fail(e);
      } else {
        this.uncertainApply = true;
        this.preview.set(null);
        this.message.set(
          'Chưa xác định kết quả. Gửi lại đúng lệnh đang giữ để đối chiếu.',
        );
      }
    } finally {
      if (generation === this.epoch) this.busy.set(false);
    }
  }
  private hasUnsavedWork() {
    return (
      !!this.pending() ||
      (!this.receipt() &&
        ((!!this.proposalText && this.proposalText !== this.sourceText()) ||
          Object.keys(this.owners).length > 0))
    );
  }
  canLeave() {
    if (!this.hasUnsavedWork()) return true;
    return window.confirm(
      this.pending()
        ? 'Lệnh áp dụng chưa rõ kết quả. Rời trang sẽ mất mã lệnh gửi lại; không tạo lệnh mới khi chưa đối chiếu. Vẫn rời trang?'
        : 'Bản chỉnh sửa hoặc lựa chọn Agent chưa được áp dụng. Rời trang sẽ mất nội dung đang sửa. Vẫn rời trang?',
    );
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(
    event: BeforeUnloadEvent,
  ) {
    if (this.hasUnsavedWork()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
  @HostListener('window:focus') focus() {
    this.preview.set(null);
    if (!this.busy() && !this.pending() && !this.receipt()) void this.load();
    else if (this.pending() && !this.busy())
      void this.admin().catch(e => this.fail(e));
  }
}

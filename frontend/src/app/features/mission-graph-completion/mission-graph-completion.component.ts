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
type Action = 'SUBMIT_REVIEW' | 'COMPLETE';
interface Output {
  taskId: string;
  title: string;
  runId: string;
  attemptId: string;
  runRevision: string;
  outputSha256: string;
  requestSha256: string;
  provenanceSha256: string;
}
interface Accounting {
  settledMicrousd: string;
  heldMicrousd: string;
  unknownMicrousd: string;
  unresolvedReservations: string;
  inputTokens: string;
  outputTokens: string;
}
interface Snapshot {
  accounting?: Accounting;
  organizationId: string;
  teamId: string;
  missionId: string;
  status: string;
  planRevision: string | null;
  controlRevision: string | null;
  taskCount: number;
  outputs: Output[];
  manifestSha256: string | null;
  blockers: string[];
  action: Action | null;
  fingerprint: string;
}
interface Command {
  idempotencyKey: string;
  action: Action;
  expectedFingerprint: string;
}
interface Receipt {
  organizationId: string;
  teamId: string;
  missionId: string;
  planRevision: string;
  idempotencyKey: string;
  action: Action;
  status: string;
  controlRevision: string;
  manifestSha256: string;
  outputs: Output[];
}
const hash = (x: unknown): x is string =>
  typeof x === 'string' && /^[0-9a-f]{64}$/.test(x);
const revision = (x: unknown): x is string =>
  typeof x === 'string' &&
  /^[1-9][0-9]{0,18}$/.test(x) &&
  BigInt(x) <= 9223372036854775807n;
@Component({
  standalone: true,
  imports: [RouterLink],
  styleUrl: '../execution-review/execution-review.component.css',
  template: `<main>
    <h1>Hoàn tất Mission theo kế hoạch</h1>
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
      Đối chiếu toàn bộ đầu ra đã ACCEPT. Đưa Mission vào REVIEW trước khi xác
      nhận COMPLETED; mỗi bước đều kiểm tra lại bằng chứng và chi phí.
    </p>
    <p role="status">{{ busy() ? 'Đang kiểm tra…' : message() }}</p>
    <button (click)="load()" [disabled]="busy()">
      Kiểm tra trạng thái hiện tại
    </button>
    @if (receipt()) {
      <p role="status">{{ receipt() }}</p>
    }
    @if (pending()) {
      <p>Kết quả gửi chưa xác định. Giữ nguyên yêu cầu để gửi lại.</p>
      <button (click)="send()" [disabled]="busy()">
        Gửi lại đúng lệnh hoàn tất
      </button>
    }
    @if (data(); as s) {
      <h2>Trạng thái hiện tại: {{ s.status }}</h2>
      <p>
        Kế hoạch {{ s.planRevision }} · {{ s.outputs.length }} /
        {{ s.taskCount }} đầu ra hợp lệ
      </p>
      @if (s.accounting; as cost) {
        <h2>Chi phí toàn Mission đã ghi nhận</h2>
        <p>Đã đối soát: {{ usd(cost.settledMicrousd) }} USD</p>
        <p>Đang giữ chỗ: {{ usd(cost.heldMicrousd) }} USD</p>
        <p>Giữ cho kết quả UNKNOWN: {{ usd(cost.unknownMicrousd) }} USD</p>
        <p>Khoản chưa đối soát: {{ cost.unresolvedReservations }}</p>
        <p>
          Input token: {{ cost.inputTokens }} · Output token:
          {{ cost.outputTokens }}
        </p>
        <p>
          Bao gồm toàn bộ lịch sử Run của Mission; khoản chưa đối soát chưa phải
          chi phí cuối cùng.
        </p>
      }
      @if (s.blockers.length) {
        <p>
          Chưa đủ điều kiện: toàn bộ Task trong kế hoạch phải có đầu ra ACCEPT
          hợp lệ, cấu hình khớp và thực thi/chi phí đã được xử lý xong.
        </p>
        <ul>
          @for (b of s.blockers; track b) {
            <li>{{ b }}</li>
          }
        </ul>
      }
      @for (o of s.outputs; track o.runId) {
        <article>
          <h3>{{ o.title }}</h3>
          <a [routerLink]="['/workspace', org, 'execution-runs', o.runId]"
            >Xem Run</a
          >
          <p>
            Output: <code>{{ o.outputSha256 }}</code>
          </p>
          <a [href]="downloadUrl(o)" download>Tải output đã ACCEPT (.txt)</a>
          <button (click)="readOutput(o)" [disabled]="busy()">
            Xem output đã ACCEPT
          </button>
        </article>
      }
      @if (s.manifestSha256) {
        <p>
          Mã bản kê: <code>{{ s.manifestSha256 }}</code>
        </p>
      }
      @if (s.action && !pending()) {
        <label
          ><input
            type="checkbox"
            [checked]="confirmed()"
            (change)="confirmed.set($any($event.target).checked)"
            [disabled]="busy()"
          />Tôi xác nhận toàn bộ bản kê đầu ra và bước chuyển trạng thái
          này.</label
        ><button (click)="apply()" [disabled]="busy() || !confirmed()">
          {{
            s.action === 'SUBMIT_REVIEW'
              ? 'Đưa Mission vào review'
              : 'Hoàn tất Mission'
          }}
        </button>
      }
    }
    @if (content() !== null) {
      <h2>Output đã ACCEPT</h2>
      <pre aria-label="Nội dung output đã ACCEPT">{{ content() }}</pre>
    }
  </main>`,
})
export class MissionGraphCompletionComponent {
  usd(value: string): string {
    const n = BigInt(value);
    return `${n / 1000000n}.${(n % 1000000n).toString().padStart(6, '0')}`;
  }
  downloadUrl(o: Output): string {
    return `/api/v1/organizations/${this.org}/teams/${this.team}/missions/${this.mission}/graph-completion/outputs/${o.runId}/download?attemptId=${o.attemptId}&outputSha256=${o.outputSha256}`;
  }
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private generation = 0;
  private account = this.auth.user()?.id;
  org = '';
  team = '';
  mission = '';
  readonly data = signal<Snapshot | null>(null);
  readonly busy = signal(false);
  readonly message = signal('');
  readonly receipt = signal('');
  readonly confirmed = signal(false);
  readonly pending = signal<Command | null>(null);
  readonly content = signal<string | null>(null);
  private expected: Snapshot | null = null;
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
    this.confirmed.set(false);
    this.pending.set(null);
    this.expected = null;
    this.content.set(null);
    this.receipt.set('');
  }
  private active(g: number, a: string | undefined) {
    return (
      !this.destroy.destroyed &&
      g === this.generation &&
      !!a &&
      a === this.auth.user()?.id
    );
  }
  private base() {
    return `/api/v1/organizations/${this.org}/teams/${this.team}/missions/${this.mission}/graph-completion`;
  }
  private async authorize() {
    if (![this.org, this.team, this.mission].every(uuid))
      throw new Error('Invalid scope');
    const {data: w} = await firstValueFrom(
      this.http
        .get<{
          data: {id: string; role: string};
        }>(`/api/v1/auth/workspaces/${this.org}`)
        .pipe(timeout(15000)),
    );
    if (w?.id !== this.org || w.role !== 'ADMIN')
      throw new HttpErrorResponse({status: 403});
  }
  private validOutputs(outputs: Output[]) {
    if (!Array.isArray(outputs) || outputs.length > 200) return false;
    const tasks = new Set<string>(),
      runs = new Set<string>();
    return outputs.every(o => {
      if (
        !uuid(o.taskId) ||
        !uuid(o.runId) ||
        !uuid(o.attemptId) ||
        !revision(o.runRevision) ||
        !hash(o.outputSha256) ||
        !hash(o.requestSha256) ||
        !hash(o.provenanceSha256) ||
        typeof o.title !== 'string' ||
        o.title.length > 255 ||
        tasks.has(o.taskId) ||
        runs.has(o.runId)
      )
        return false;
      tasks.add(o.taskId);
      runs.add(o.runId);
      return true;
    });
  }
  private valid(s: Snapshot) {
    return (
      !!s &&
      (s.accounting === undefined ||
        (!!s.accounting &&
          [
            'settledMicrousd',
            'heldMicrousd',
            'unknownMicrousd',
            'unresolvedReservations',
            'inputTokens',
            'outputTokens',
          ].every(
            k =>
              typeof s.accounting![k as keyof Accounting] === 'string' &&
              /^(0|[1-9][0-9]{0,99})$/.test(
                s.accounting![k as keyof Accounting],
              ),
          ))) &&
      s.organizationId === this.org &&
      s.teamId === this.team &&
      s.missionId === this.mission &&
      typeof s.status === 'string' &&
      (s.planRevision === null || revision(s.planRevision)) &&
      (s.controlRevision === null || revision(s.controlRevision)) &&
      Number.isSafeInteger(s.taskCount) &&
      s.taskCount >= 0 &&
      s.taskCount <= 200 &&
      this.validOutputs(s.outputs) &&
      s.outputs.length <= s.taskCount &&
      (s.manifestSha256 === null || hash(s.manifestSha256)) &&
      hash(s.fingerprint) &&
      Array.isArray(s.blockers) &&
      s.blockers.every(b => typeof b === 'string') &&
      (s.action === null ||
        (['SUBMIT_REVIEW', 'COMPLETE'].includes(s.action) &&
          !s.blockers.length &&
          revision(s.planRevision) &&
          revision(s.controlRevision) &&
          hash(s.manifestSha256) &&
          s.taskCount > 0 &&
          s.outputs.length === s.taskCount &&
          (s.action === 'COMPLETE'
            ? s.status === 'REVIEW'
            : ['RUNNING', 'WAITING_APPROVAL'].includes(s.status))))
    );
  }
  private fail(e: unknown) {
    this.data.set(null);
    this.content.set(null);
    this.confirmed.set(false);
    if (e instanceof HttpErrorResponse && [401, 403].includes(e.status)) {
      this.clear();
      this.message.set('Cần quyền ADMIN hiện hành.');
    } else
      this.message.set(
        'Không xác nhận được bản kê hiện tại. Hãy kiểm tra lại.',
      );
  }
  @HostListener('window:focus') focus() {
    if (!this.busy()) void this.load();
  }
  @HostListener('document:visibilitychange') visible() {
    if (document.visibilityState === 'visible') this.focus();
  }
  async load() {
    if (this.busy()) return;
    const g = ++this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    this.data.set(null);
    this.content.set(null);
    this.confirmed.set(false);
    this.message.set('');
    try {
      await this.authorize();
      if (!this.active(g, a)) return;
      const {data: s} = await firstValueFrom(
        this.http.get<{data: Snapshot}>(this.base()).pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (!this.valid(s)) throw new Error('Invalid manifest');
      this.data.set(s);
    } catch (e) {
      if (this.active(g, a)) this.fail(e);
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
  }
  apply() {
    const s = this.data();
    if (!s?.action || !this.confirmed() || this.busy() || this.pending())
      return;
    this.expected = s;
    this.pending.set({
      idempotencyKey: crypto.randomUUID(),
      action: s.action,
      expectedFingerprint: s.fingerprint,
    });
    void this.send();
  }
  private identity(outputs: Output[]) {
    return outputs.map(o => [
      o.taskId,
      o.title,
      o.runId,
      o.attemptId,
      o.runRevision,
      o.outputSha256,
      o.requestSha256,
      o.provenanceSha256,
    ]);
  }
  async send() {
    const p = this.pending(),
      expected = this.expected;
    if (!p || !expected || this.busy()) return;
    const g = this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    this.confirmed.set(false);
    this.message.set('');
    let done = false;
    try {
      await this.authorize();
      if (!this.active(g, a)) return;
      const {data: c} = await firstValueFrom(
        this.http
          .get<{data: {headerName: string; token: string}}>('/api/v1/auth/csrf')
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (
        c?.headerName !== 'X-CSRF-TOKEN' ||
        typeof c.token !== 'string' ||
        !c.token ||
        c.token.length > 4096
      )
        throw new Error('Invalid CSRF');
      const {data: r} = await firstValueFrom(
        this.http
          .post<{
            data: Receipt;
          }>(this.base(), p, {
            headers: new HttpHeaders().set(c.headerName, c.token),
          })
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (
        !r ||
        r.organizationId !== this.org ||
        r.teamId !== this.team ||
        r.missionId !== this.mission ||
        r.idempotencyKey !== p.idempotencyKey ||
        r.action !== p.action ||
        r.status !== (p.action === 'SUBMIT_REVIEW' ? 'REVIEW' : 'COMPLETED') ||
        r.planRevision !== expected.planRevision ||
        !revision(r.controlRevision) ||
        BigInt(r.controlRevision) !== BigInt(expected.controlRevision!) + 1n ||
        r.manifestSha256 !== expected.manifestSha256 ||
        !this.validOutputs(r.outputs) ||
        JSON.stringify(this.identity(r.outputs)) !==
          JSON.stringify(this.identity(expected.outputs))
      )
        throw new Error('Unconfirmed receipt');
      this.pending.set(null);
      this.expected = null;
      this.receipt.set(
        `Đã ghi nhận ${r.status} cho bản kê ${r.manifestSha256}.`,
      );
      done = true;
    } catch (e) {
      if (!this.active(g, a)) return;
      this.data.set(null);
      this.content.set(null);
      if (
        e instanceof HttpErrorResponse &&
        [400, 401, 403, 404, 409].includes(e.status)
      ) {
        this.pending.set(null);
        this.expected = null;
        this.fail(e);
      } else
        this.message.set(
          'Kết quả gửi chưa xác định. Giữ đúng lệnh để gửi lại.',
        );
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
    if (done && this.active(g, a)) await this.load();
  }
  async readOutput(o: Output) {
    if (this.busy()) return;
    const g = this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    this.content.set(null);
    try {
      await this.authorize();
      if (!this.active(g, a)) return;
      const {data: r} = await firstValueFrom(
        this.http
          .get<{
            data: {
              runId: string;
              attemptId: string;
              outputSha256: string;
              content: string;
            };
          }>(this.base() + `/outputs/${o.runId}`, {
            params: {attemptId: o.attemptId, outputSha256: o.outputSha256},
          })
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (
        !r ||
        r.runId !== o.runId ||
        r.attemptId !== o.attemptId ||
        r.outputSha256 !== o.outputSha256 ||
        typeof r.content !== 'string'
      )
        throw new Error('Invalid output');
      const bytes = new TextEncoder().encode(r.content);
      if (bytes.length > 262144) throw new Error('Output limit');
      const digest = Array.from(
        new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
        b => b.toString(16).padStart(2, '0'),
      ).join('');
      if (!this.active(g, a)) return;
      if (digest !== o.outputSha256) throw new Error('Output integrity');
      this.content.set(r.content);
    } catch (e) {
      if (this.active(g, a)) this.fail(e);
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
  }
}

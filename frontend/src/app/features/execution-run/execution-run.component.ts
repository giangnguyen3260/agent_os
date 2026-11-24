import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  HostListener,
  effect,
  inject,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom, timeout} from 'rxjs';
import {AuthService} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
interface Accounting {
  settledMicrousd: string;
  heldMicrousd: string;
  unknownMicrousd: string;
  unresolvedReservations: string;
  inputTokens: string;
  outputTokens: string;
}
interface Dispatch {
  id: string;
  state: string;
  sourceRevision: string;
  createdAt: string;
  handedOff: boolean;
}
interface ToolEffect {
  id: string;
  attemptId: string;
  tool: string;
  version: number;
  inputSha256: string;
  utf8Bytes: number;
  codePoints: number;
  lines: number;
  recordedAt: string;
  actualMicrousd: string;
}
interface Run {
  toolEffects?: ToolEffect[];
  dispatches?: Dispatch[];
  accounting?: Accounting;
  id: string;
  teamId: string;
  missionId: string;
  taskId: string;
  status: string;
  revision: string;
  waitReason: string | null;
  attempts: {id: string; number: number; status: string}[];
}
@Component({
  standalone: true,
  imports: [RouterLink],
  styleUrl: '../execution-review/execution-review.component.css',
  template: `
    <main>
      <h1>Chi tiết lần chạy</h1>
      <button (click)="load()" [disabled]="busy()">Tải lại</button>
      <p role="status">{{ busy() ? 'Đang tải…' : message() }}</p>
      @if (data(); as run) {
        <a
          [routerLink]="[
            '/workspace',
            org,
            'teams',
            run.teamId,
            'missions',
            run.missionId,
            'tasks',
            run.taskId,
          ]"
          >Về Task</a
        >
        <dl>
          <dt>Run</dt>
          <dd>{{ run.id }}</dd>
          <dt>Trạng thái</dt>
          <dd>{{ run.status }}</dd>
          <dt>Phiên bản</dt>
          <dd>{{ run.revision }}</dd>
          <dt>Lý do chờ</dt>
          <dd>{{ run.waitReason ?? 'Không có' }}</dd>
        </dl>
        @if (run.status === 'CREATED') {
          <a
            [routerLink]="[
              '/workspace',
              org,
              'execution-runs',
              run.id,
              'start',
            ]"
            >Khởi chạy Task</a
          >
        }
        @if (run.status === 'IN_REVIEW') {
          <a
            [routerLink]="[
              '/workspace',
              org,
              'execution-runs',
              run.id,
              'review',
            ]"
            >Duyệt kết quả thực thi</a
          >
        }
        @if (run.status === 'REWORK_REQUIRED') {
          <p>Đã yêu cầu làm lại. Chưa có lần chạy tiếp theo được xác nhận.</p>
        }
        @if (run.status === 'COMPLETED') {
          <p>Kết quả của lần chạy đã được chấp nhận.</p>
        }
        @if (run.accounting; as cost) {
          <h2>Chi phí và token đã ghi nhận</h2>
          <p>Đã đối soát: {{ usd(cost.settledMicrousd) }} USD</p>
          <p>Đang giữ chỗ: {{ usd(cost.heldMicrousd) }} USD</p>
          <p>Giữ cho kết quả UNKNOWN: {{ usd(cost.unknownMicrousd) }} USD</p>
          <p>Khoản chưa đối soát: {{ cost.unresolvedReservations }}</p>
          <p>
            Input token: {{ cost.inputTokens }} · Output token:
            {{ cost.outputTokens }}
          </p>
          <p>
            Số liệu từ ledger và usage đã ghi nhận; khoản chưa đối soát chưa
            phải chi phí cuối cùng.
          </p>
        }
        @if (run.status === 'CREATED') {
          <a
            [routerLink]="[
              '/workspace',
              org,
              'execution-runs',
              run.id,
              'text-tool-approval',
            ]"
            >Duyệt text.metrics trước khi Start</a
          >
        }
        @if (run.toolEffects; as effects) {
          <h2>100 kết quả Tool gần nhất</h2>
          <p>
            Số liệu đã ghi nhận; kết quả Tool không thay thế ACCEPT output của
            Run.
          </p>
          @for (e of effects; track e.id) {
            <article>
              <p>{{ e.tool }} v{{ e.version }} · Attempt {{ e.attemptId }}</p>
              <p>SHA đầu vào: {{ e.inputSha256 }}</p>
              <p>
                {{ e.utf8Bytes }} byte UTF-8 · {{ e.codePoints }} ký tự Unicode
                · {{ e.lines }} dòng
              </p>
              <p>{{ e.recordedAt }} · Chi phí đã đối soát: 0 USD</p>
            </article>
          }
          @if (effects.length === 0) {
            <p>Chưa có kết quả Tool đã ghi nhận.</p>
          }
        }
        @if (run.dispatches; as commands) {
          <h2>100 lệnh Start gần nhất</h2>
          <p>
            FINISHED biểu thị lệnh đã kết thúc xử lý; xem trạng thái Attempt và
            output để biết kết quả.
          </p>
          @for (d of commands; track d.id) {
            <article>
              <p>
                {{ d.id }} · {{ d.state }} · Phiên bản nguồn
                {{ d.sourceRevision }}
              </p>
              <p>
                {{ d.createdAt }} ·
                {{
                  d.handedOff
                    ? 'Đã ghi nhận handoff tới provider'
                    : 'Chưa ghi nhận handoff tới provider'
                }}
              </p>
              @if (d.state === 'QUEUED') {
                <p>
                  Đang chờ worker nhận lệnh; kiểm tra điều khiển thực thi
                  Mission.
                </p>
              }
              @if (d.state === 'CLAIMED') {
                <p>Worker đã nhận lệnh; chờ kết quả hoặc đối soát.</p>
              }
              @if (d.state === 'UNKNOWN') {
                <p>
                  Kết quả request chưa rõ. Cần đối soát trước khi quyết định
                  chạy lại.
                </p>
              }
            </article>
          } @empty {
            <p>Chưa có lệnh Start được ghi nhận.</p>
          }
        }
        <h2>100 attempt gần nhất</h2>
        <ul>
          @for (attempt of run.attempts; track attempt.id) {
            <li>
              Attempt {{ attempt.number }} · {{ attempt.status }} ·
              {{ attempt.id }}
            </li>
          } @empty {
            <li>Chưa có attempt.</li>
          }
        </ul>
      }
    </main>
  `,
})
export class ExecutionRunComponent {
  usd(value: string): string {
    const n = BigInt(value);
    return `${n / 1000000n}.${(n % 1000000n).toString().padStart(6, '0')}`;
  }
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private generation = 0;
  private account = this.auth.user()?.id;
  org = '';
  readonly data = signal<Run | null>(null);
  readonly busy = signal(false);
  readonly message = signal('');
  constructor() {
    effect(() => {
      const account = this.auth.user()?.id;
      if (account !== this.account) {
        this.account = account;
        this.clear();
        this.message.set('Phiên đăng nhập đã thay đổi.');
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(() => {
      this.clear();
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
  @HostListener('window:focus') focus() {
    if (!this.busy()) void this.load();
  }
  @HostListener('document:visibilitychange') visible() {
    if (document.visibilityState === 'visible') this.focus();
  }
  async load() {
    if (this.busy()) return;
    this.clear();
    const generation = this.generation,
      account = this.auth.user()?.id;
    const active = () =>
      generation === this.generation &&
      !this.destroy.destroyed &&
      !!account &&
      account === this.auth.user()?.id;
    const p = this.route.snapshot.paramMap;
    this.org = p.get('id') || '';
    const runId = p.get('runId'),
      team = p.get('teamId'),
      mission = p.get('missionId'),
      task = p.get('taskId');
    this.busy.set(true);
    try {
      if (
        !uuid(this.org) ||
        (runId ? !uuid(runId) : !uuid(team) || !uuid(mission) || !uuid(task))
      )
        throw new Error('invalid');
      const context = await this.auth.getWorkspace(this.org);
      if (!active()) return;
      if (context?.id !== this.org || context.role !== 'ADMIN')
        throw new Error('denied');
      const base = '/api/v1/organizations/' + this.org;
      const path = runId
        ? base + '/execution-runs/' + runId
        : base +
          '/teams/' +
          team +
          '/missions/' +
          mission +
          '/tasks/' +
          task +
          '/execution-run';
      const {data} = await firstValueFrom(
        this.http.get<{data: Run}>(path).pipe(timeout(15000)),
      );
      if (!active()) return;
      if (
        !data ||
        (data.toolEffects !== undefined &&
          (!Array.isArray(data.toolEffects) ||
            data.toolEffects.length > 100 ||
            new Set(data.toolEffects.map(e => e?.id)).size !==
              data.toolEffects.length ||
            data.toolEffects.some(
              e =>
                !e ||
                !uuid(e.id) ||
                !uuid(e.attemptId) ||
                e.tool !== 'text.metrics' ||
                e.version !== 1 ||
                typeof e.inputSha256 !== 'string' ||
                !/^[0-9a-f]{64}$/.test(e.inputSha256) ||
                ![e.utf8Bytes, e.codePoints, e.lines].every(
                  Number.isSafeInteger,
                ) ||
                e.utf8Bytes < 0 ||
                e.utf8Bytes > 8192 ||
                e.codePoints < 0 ||
                e.codePoints > e.utf8Bytes ||
                e.lines < 0 ||
                e.lines > e.codePoints + 1 ||
                e.actualMicrousd !== '0' ||
                !Number.isFinite(Date.parse(e.recordedAt)),
            ))) ||
        (data.dispatches !== undefined &&
          (!Array.isArray(data.dispatches) ||
            data.dispatches.length > 100 ||
            new Set(data.dispatches.map(d => d.id)).size !==
              data.dispatches.length ||
            data.dispatches.some(
              d =>
                !d ||
                !uuid(d.id) ||
                !['QUEUED', 'CLAIMED', 'UNKNOWN', 'FINISHED'].includes(
                  d.state,
                ) ||
                typeof d.sourceRevision !== 'string' ||
                !/^(0|[1-9][0-9]{0,18})$/.test(d.sourceRevision) ||
                BigInt(d.sourceRevision) > 9223372036854775807n ||
                typeof d.createdAt !== 'string' ||
                !Number.isFinite(Date.parse(d.createdAt)) ||
                typeof d.handedOff !== 'boolean',
            ))) ||
        (data.accounting !== undefined &&
          (!data.accounting ||
            ![
              'settledMicrousd',
              'heldMicrousd',
              'unknownMicrousd',
              'unresolvedReservations',
              'inputTokens',
              'outputTokens',
            ].every(
              key =>
                typeof data.accounting![key as keyof Accounting] === 'string' &&
                /^(0|[1-9][0-9]{0,99})$/.test(
                  data.accounting![key as keyof Accounting],
                ),
            ))) ||
        !uuid(data.id) ||
        !uuid(data.teamId) ||
        !uuid(data.missionId) ||
        !uuid(data.taskId) ||
        (runId
          ? data.id !== runId
          : data.teamId !== team ||
            data.missionId !== mission ||
            data.taskId !== task) ||
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
        ].includes(data.status) ||
        typeof data.revision !== 'string' ||
        !/^(0|[1-9]\d*)$/.test(data.revision) ||
        (data.status === 'WAITING'
          ? !['INPUT', 'APPROVAL'].includes(data.waitReason || '')
          : data.waitReason !== null) ||
        !Array.isArray(data.attempts) ||
        data.attempts.length > 100 ||
        !data.attempts.every(
          (a, i) =>
            uuid(a.id) &&
            Number.isSafeInteger(a.number) &&
            a.number > 0 &&
            [
              'CREATED',
              'RUNNING',
              'WAITING_APPROVAL',
              'WAITING_INPUT',
              'SUCCEEDED',
              'FAILED',
              'CANCELLING',
              'CANCELLED',
              'UNKNOWN',
            ].includes(a.status) &&
            (i === 0 || a.number < data.attempts[i - 1].number),
        ) ||
        new Set(data.attempts.map(a => a.id)).size !== data.attempts.length
      )
        throw new Error('invalid');
      this.data.set(data);
    } catch (error) {
      if (active())
        this.message.set(
          error instanceof HttpErrorResponse && error.status === 404
            ? 'Task chưa có lần chạy được ghi nhận.'
            : 'Không tải được lần chạy. Cần quyền ADMIN hiện hành; hãy thử tải lại.',
        );
    } finally {
      if (active()) this.busy.set(false);
    }
  }
}

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
interface Snapshot {
  organizationId: string;
  teamId: string;
  missionId: string;
  status: string;
  controlRevision: string | null;
  taskId: string | null;
  runId: string | null;
  attemptId: string | null;
  outputSha256: string | null;
  outputText: string | null;
  action: Action | null;
  blockers: string[];
  fingerprint: string;
}
interface Command {
  idempotencyKey: string;
  action: Action;
  expectedFingerprint: string;
}
interface Receipt {
  missionId: string;
  action: Action;
  status: string;
  controlRevision: string;
  taskId: string;
  runId: string;
  attemptId: string;
  outputSha256: string;
}
const hash = (v: unknown): v is string =>
  typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
const revision = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[1-9]\d{0,18}$/.test(v) &&
  BigInt(v) <= 9223372036854775807n;
const reasons: Record<string, string> = {
  PARENT_UNAVAILABLE: 'Workspace hoặc Team hiện không cho phép hoàn tất.',
  MISSION_STATE_UNAVAILABLE: 'Trạng thái Mission chưa hỗ trợ bước hoàn tất.',
  EXECUTION_CONTROL_UNAVAILABLE:
    'Điều khiển thực thi chưa sẵn sàng hoặc đang yêu cầu hủy.',
  UNSUPPORTED_SCOPE:
    'Phiên bản này yêu cầu Mission có đúng một Task và một lần chạy.',
  ACCEPTED_OUTPUT_UNAVAILABLE:
    'Chưa có đầy đủ bằng chứng thực thi và output được ACCEPT khớp cấu hình hiện tại.',
  OUTPUT_INTEGRITY_UNAVAILABLE: 'Không xác nhận được tính toàn vẹn của output.',
  REQUEST_INTEGRITY_UNAVAILABLE: 'Không xác nhận được request thực thi đã lưu.',
  USAGE_EVIDENCE_UNAVAILABLE: 'Bằng chứng sử dụng và chi phí chưa khớp.',
  ATTEMPT_UNRESOLVED: 'Còn lần thử chưa có kết quả cuối cùng.',
  LIVE_CLAIM: 'Worker vẫn đang giữ quyền thực thi.',
  ACCOUNTING_UNRESOLVED: 'Còn ngân sách đang giữ hoặc chờ đối soát.',
  DISPATCH_UNRESOLVED: 'Còn lệnh thực thi chưa kết thúc.',
  COMPLETION_REVIEW_UNAVAILABLE:
    'Chưa có biên nhận review Mission khớp bằng chứng hiện tại.',
};
@Component({
  standalone: true,
  imports: [RouterLink],
  styleUrl: '../execution-review/execution-review.component.css',
  styles: [
    'dd {margin: 4px 0 16px; overflow-wrap: anywhere;} code {overflow-wrap: anywhere;}',
  ],
  template: `<main>
    <h1>Hoàn tất Mission</h1>
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
      Áp dụng cho Mission có đúng một Task không có phụ thuộc. Output Task phải
      được ACCEPT và mọi lần chạy, lệnh, ngân sách phải được xử lý xong.
    </p>
    <p role="status">{{ busy() ? 'Đang kiểm tra…' : message() }}</p>
    <button (click)="load()" [disabled]="busy()">
      Kiểm tra trạng thái hiện tại
    </button>
    @if (receipt(); as r) {
      <section aria-label="Biên nhận thao tác">
        <h2>Thao tác đã được ghi nhận</h2>
        <p>
          Biên nhận {{ r.action }}: {{ r.status }} · revision
          {{ r.controlRevision }}. Đây là kết quả lịch sử của lệnh; trạng thái
          hiện tại hiển thị bên dưới.
        </p>
      </section>
    }
    @if (pending()) {
      <section>
        <h2>Kết quả gửi chưa xác định</h2>
        <p>
          Giữ nguyên lệnh đã gửi. Bạn có thể kiểm tra trạng thái hiện tại hoặc
          gửi lại đúng lệnh này; không tạo thao tác thay thế.
        </p>
        <button (click)="send()" [disabled]="busy()">Gửi lại đúng lệnh</button>
      </section>
    }
    @if (data(); as s) {
      <section aria-label="Trạng thái hoàn tất Mission">
        <h2>Trạng thái hiện tại: {{ s.status }}</h2>
        @if (s.runId) {
          <a [routerLink]="['/workspace', org, 'execution-runs', s.runId]"
            >Xem lần chạy của Task</a
          >
        }
        @if (s.taskId) {
          <dl>
            <dt>Task</dt>
            <dd>{{ s.taskId }}</dd>
            <dt>Output đã ACCEPT</dt>
            <dd>
              <code>{{ s.outputSha256 }}</code>
            </dd>
          </dl>
        }
        @if (s.outputText) {
          <h3>Nội dung đã được duyệt</h3>
          <pre tabindex="0" aria-label="Output đã ACCEPT">{{
            s.outputText
          }}</pre>
        }
        @if (s.blockers.length) {
          <h3>Chưa thể hoàn tất</h3>
          <ul>
            @for (reason of s.blockers; track reason) {
              <li>{{ reasonText(reason) }}</li>
            }
          </ul>
        }
        @if (s.status === 'COMPLETED' && !s.blockers.length) {
          <p>Mission đã hoàn tất. Không có thao tác mở lại.</p>
        }
        @if (s.action && !pending()) {
          @if (!confirm()) {
            <button (click)="confirm.set(true)" [disabled]="busy()">
              {{
                s.action === 'SUBMIT_REVIEW'
                  ? 'Đưa Mission vào review'
                  : 'Hoàn tất Mission'
              }}
            </button>
          } @else {
            <section aria-label="Xác nhận chuyển trạng thái">
              <p>
                Xác nhận Mission {{ mission }} chuyển sang
                {{ s.action === 'SUBMIT_REVIEW' ? 'REVIEW' : 'COMPLETED' }} với
                đúng output đã hiển thị?
              </p>
              <button (click)="commit()" [disabled]="busy()">Xác nhận</button
              ><button (click)="confirm.set(false)" [disabled]="busy()">
                Quay lại
              </button>
            </section>
          }
        }
      </section>
    }
  </main>`,
})
export class MissionCompletionComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private generation = 0;
  private expected: Pick<
    Receipt,
    'taskId' | 'runId' | 'attemptId' | 'outputSha256' | 'controlRevision'
  > | null = null;
  private account = this.auth.user()?.id;
  org = '';
  team = '';
  mission = '';
  readonly data = signal<Snapshot | null>(null);
  readonly pending = signal<Command | null>(null);
  readonly receipt = signal<Receipt | null>(null);
  readonly confirm = signal(false);
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
    this.pending.set(null);
    this.receipt.set(null);
    this.expected = null;
    this.confirm.set(false);
    this.busy.set(false);
    this.message.set('');
  }
  private active(g: number, a: string | undefined) {
    return (
      g === this.generation &&
      !this.destroy.destroyed &&
      !!a &&
      a === this.auth.user()?.id
    );
  }
  private base() {
    return `/api/v1/organizations/${this.org}/teams/${this.team}/missions/${this.mission}/completion`;
  }
  private async authorize() {
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
  reasonText(code: string) {
    return (
      reasons[code] ||
      'Có điều kiện chưa được xác nhận. Hãy kiểm tra lại Mission.'
    );
  }
  @HostListener('window:focus') focus() {
    if (!this.busy()) void this.load();
  }
  @HostListener('document:visibilitychange') visible() {
    if (document.visibilityState === 'visible') this.focus();
  }
  private valid(s: Snapshot) {
    return (
      !!s &&
      s.organizationId === this.org &&
      s.teamId === this.team &&
      s.missionId === this.mission &&
      [
        'CREATED',
        'PLANNING',
        'READY',
        'RUNNING',
        'WAITING_APPROVAL',
        'REVIEW',
        'COMPLETED',
        'FAILED',
        'CANCELLED',
      ].includes(s.status) &&
      (s.controlRevision === null || revision(s.controlRevision)) &&
      [s.taskId, s.runId, s.attemptId].every(v => v === null || uuid(v)) &&
      (s.outputSha256 === null || hash(s.outputSha256)) &&
      (s.outputText === null ||
        (typeof s.outputText === 'string' &&
          !!s.outputText.trim() &&
          new TextEncoder().encode(s.outputText).length <= 262144 &&
          hash(s.outputSha256))) &&
      Array.isArray(s.blockers) &&
      s.blockers.length <= 20 &&
      new Set(s.blockers).size === s.blockers.length &&
      s.blockers.every(
        v => typeof v === 'string' && Object.hasOwn(reasons, v),
      ) &&
      hash(s.fingerprint) &&
      (s.action === null ||
        (!s.blockers.length &&
          revision(s.controlRevision) &&
          uuid(s.taskId) &&
          uuid(s.runId) &&
          uuid(s.attemptId) &&
          hash(s.outputSha256) &&
          !!s.outputText &&
          ((s.action === 'SUBMIT_REVIEW' &&
            ['RUNNING', 'WAITING_APPROVAL'].includes(s.status)) ||
            (s.action === 'COMPLETE' && s.status === 'REVIEW'))))
    );
  }
  async load() {
    if (this.busy()) return;
    const g = ++this.generation,
      a = this.auth.user()?.id;
    this.data.set(null);
    this.confirm.set(false);
    this.busy.set(true);
    this.message.set('');
    try {
      if (![this.org, this.team, this.mission].every(uuid))
        throw new Error('Invalid route');
      await this.authorize();
      if (!this.active(g, a)) return;
      const r = await firstValueFrom(
        this.http.get<{data: Snapshot}>(this.base()).pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (!this.valid(r?.data)) throw new Error('Invalid assessment');
      if (r.data.outputText !== null) {
        const digest = Array.from(
          new Uint8Array(
            await crypto.subtle.digest(
              'SHA-256',
              new TextEncoder().encode(r.data.outputText),
            ),
          ),
          b => b.toString(16).padStart(2, '0'),
        ).join('');
        if (!this.active(g, a)) return;
        if (digest !== r.data.outputSha256)
          throw new Error('Invalid output digest');
      }
      this.data.set(r.data);
    } catch (e) {
      if (this.active(g, a)) this.fail(e);
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
  }
  private fail(e: unknown) {
    this.data.set(null);
    this.confirm.set(false);
    if (e instanceof HttpErrorResponse && [401, 403].includes(e.status)) {
      this.pending.set(null);
      this.receipt.set(null);
      this.expected = null;
      this.message.set(
        'Cần quyền ADMIN hiện hành để xem hoặc hoàn tất Mission.',
      );
    } else
      this.message.set(
        e instanceof HttpErrorResponse && e.status === 404
          ? 'Mission hoặc tính năng hoàn tất chưa khả dụng.'
          : 'Không xác nhận được điều kiện hiện tại. Hãy kiểm tra lại.',
      );
  }
  commit() {
    const s = this.data();
    if (!s?.action || !this.confirm() || this.busy() || this.pending()) return;
    this.expected = {
      taskId: s.taskId!,
      runId: s.runId!,
      attemptId: s.attemptId!,
      outputSha256: s.outputSha256!,
      controlRevision: (BigInt(s.controlRevision!) + 1n).toString(),
    };
    this.pending.set(
      Object.freeze({
        idempotencyKey: crypto.randomUUID(),
        action: s.action,
        expectedFingerprint: s.fingerprint,
      }),
    );
    this.confirm.set(false);
    this.data.set(null);
    void this.send();
  }
  async send() {
    const p = this.pending();
    if (!p || this.busy()) return;
    const g = this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    this.message.set('');
    let accepted = false;
    try {
      await this.authorize();
      if (!this.active(g, a)) return;
      const csrf = await firstValueFrom(
        this.http
          .get<{data: {headerName: string; token: string}}>('/api/v1/auth/csrf')
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (
        !csrf?.data ||
        csrf.data.headerName !== 'X-CSRF-TOKEN' ||
        typeof csrf.data.token !== 'string' ||
        !csrf.data.token ||
        csrf.data.token.length > 4096
      )
        throw new Error('Invalid CSRF');
      const {data: r} = await firstValueFrom(
        this.http
          .post<{
            data: Receipt;
          }>(this.base(), p, {
            headers: new HttpHeaders().set(
              csrf.data.headerName,
              csrf.data.token,
            ),
          })
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (
        !r ||
        r.missionId !== this.mission ||
        r.action !== p.action ||
        r.status !== (p.action === 'SUBMIT_REVIEW' ? 'REVIEW' : 'COMPLETED') ||
        !revision(r.controlRevision) ||
        !uuid(r.taskId) ||
        !uuid(r.runId) ||
        !uuid(r.attemptId) ||
        !hash(r.outputSha256) ||
        !this.expected ||
        r.taskId !== this.expected.taskId ||
        r.runId !== this.expected.runId ||
        r.attemptId !== this.expected.attemptId ||
        r.outputSha256 !== this.expected.outputSha256 ||
        r.controlRevision !== this.expected.controlRevision
      )
        throw new Error('Unconfirmed receipt');
      this.receipt.set(r);
      this.pending.set(null);
      accepted = true;
    } catch (e) {
      if (!this.active(g, a)) return;
      if (
        e instanceof HttpErrorResponse &&
        [400, 401, 403, 404, 409].includes(e.status)
      ) {
        this.pending.set(null);
        this.fail(e);
      } else {
        this.data.set(null);
        this.message.set(
          'Kết quả gửi chưa xác định. Giữ đúng lệnh để kiểm tra hoặc gửi lại.',
        );
      }
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
    if (accepted && this.active(g, a)) await this.load();
  }
}

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
type Operation = 'ISSUE' | 'REVOKE';
interface Scope {
  organizationId: string;
  teamId: string;
  missionId: string;
  agentId: string;
}
interface Context {
  scope: Scope;
  tool: string;
  version: number;
  capability: string;
  action: string;
  grant: {
    id: string;
    issuedAt: string;
    expiresAt: string;
    revokedAt: string | null;
  } | null;
  canIssue: boolean;
  canRevoke: boolean;
  fingerprint: string;
}
interface Command {
  idempotencyKey: string;
  operation: Operation;
  expectedFingerprint: string;
}
interface Receipt {
  scope: Scope;
  idempotencyKey: string;
  operation: Operation;
  grantId: string;
  recordedAt: string;
}
@Component({
  standalone: true,
  imports: [RouterLink],
  selector: 'app-text-tool-grants',
  template: ` <main>
    <a
      [routerLink]="[
        '/workspace',
        scope.organizationId,
        'teams',
        scope.teamId,
        'missions',
        scope.missionId,
        'plan-review',
      ]"
      >Về kế hoạch Mission</a
    >
    <h1>Quyền công cụ phân tích văn bản</h1>
    <p>
      text.metrics v1 · Đếm byte UTF-8, ký tự Unicode và dòng. Văn bản tối đa 8
      KiB; không gửi ra dịch vụ ngoài.
    </p>
    <p>
      Quyền chỉ dành cho Agent trong Mission này, hết hạn sau một giờ. Cấp quyền
      không khởi chạy công cụ. Luồng Agent tự gọi Tool đang chờ tích hợp.
    </p>
    <p role="status">{{ message() }}</p>
    @if (context(); as c) {
      <section>
        <h2>Quyền hiện tại</h2>
        <p>Agent: {{ c.scope.agentId }}</p>
        @if (c.grant; as g) {
          <p>Mã quyền: {{ g.id }}</p>
          <p>Hết hạn: {{ g.expiresAt }}</p>
          @if (g.revokedAt) {
            <p>Đã thu hồi: {{ g.revokedAt }}</p>
          }
        } @else {
          <p>Chưa cấp quyền.</p>
        }
        @if (!pending()) {
          <label
            ><input
              type="checkbox"
              [checked]="confirmed()"
              (change)="confirmed.set($any($event.target).checked)"
              [disabled]="busy()"
            />
            Tôi xác nhận thay đổi quyền cho đúng Agent và Mission này.</label
          >
          @if (c.canIssue) {
            <button
              (click)="begin('ISSUE')"
              [disabled]="busy() || !confirmed()"
            >
              Cấp quyền trong một giờ
            </button>
          }
          @if (c.canRevoke) {
            <button
              (click)="begin('REVOKE')"
              [disabled]="busy() || !confirmed()"
            >
              Thu hồi quyền
            </button>
          }
        }
      </section>
    }
    @if (pending()) {
      <p>
        Kết quả lệnh chưa xác định. Giữ nguyên lệnh để kiểm tra hoặc gửi lại.
      </p>
      <button (click)="send()" [disabled]="busy()">Gửi lại đúng lệnh</button>
    }
    <button (click)="load()" [disabled]="busy()">
      Kiểm tra quyền hiện tại
    </button>
    @if (receipt(); as r) {
      <section>
        <h2>Biên nhận thay đổi quyền</h2>
        <p>{{ r.operation }} · {{ r.grantId }} · {{ r.recordedAt }}</p>
        <p>Biên nhận ghi nhận thao tác; quyền hiện hành được kiểm tra riêng.</p>
      </section>
    }
  </main>`,
  styles: [
    `
      main {
        max-width: 900px;
        margin: 24px auto;
        padding: 24px;
        color: #173b32;
      }
      section {
        padding: 16px;
        border: 1px solid #c5ded3;
        margin: 16px 0;
      }
      p,
      a {
        overflow-wrap: anywhere;
      }
      button {
        margin: 8px;
        padding: 10px;
      }
      label {
        display: block;
      }
    `,
  ],
})
export class TextToolGrantsComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  scope: Scope = {organizationId: '', teamId: '', missionId: '', agentId: ''};
  readonly context = signal<Context | null>(null);
  readonly receipt = signal<Receipt | null>(null);
  readonly pending = signal<Command | null>(null);
  readonly confirmed = signal(false);
  readonly busy = signal(false);
  readonly message = signal('');
  private epoch = 0;
  private uncertain = false;
  constructor() {
    let account = this.auth.user()?.id;
    effect(() => {
      const next = this.auth.user()?.id;
      if (next !== account) {
        account = next;
        this.epoch++;
        this.clear();
        this.pending.set(null);
        this.uncertain = false;
        this.busy.set(false);
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(p => {
      this.epoch++;
      this.clear();
      this.pending.set(null);
      this.scope = {
        organizationId: p.get('id') || '',
        teamId: p.get('teamId') || '',
        missionId: p.get('missionId') || '',
        agentId: p.get('agentId') || '',
      };
      void this.load();
    });
  }
  private clear() {
    this.context.set(null);
    this.receipt.set(null);
    this.confirmed.set(false);
  }
  private base() {
    const s = this.scope;
    return `/api/v1/organizations/${s.organizationId}/teams/${s.teamId}/missions/${s.missionId}/text-tool-grants/${s.agentId}`;
  }
  private active(e: number, a: string | undefined) {
    return (
      !this.destroy.destroyed &&
      e === this.epoch &&
      !!a &&
      a === this.auth.user()?.id
    );
  }
  private sameScope(s: Scope) {
    return (
      !!s &&
      Object.keys(this.scope).every(
        k => s[k as keyof Scope] === this.scope[k as keyof Scope],
      )
    );
  }
  private async authorize() {
    if (!Object.values(this.scope).every(uuid)) throw new Error('scope');
    const {data} = await firstValueFrom(
      this.http
        .get<{
          data: {id: string; role: string};
        }>(`/api/v1/auth/workspaces/${this.scope.organizationId}`)
        .pipe(timeout(15000)),
    );
    if (data?.id !== this.scope.organizationId || data.role !== 'ADMIN')
      throw new HttpErrorResponse({status: 403});
  }
  private valid(c: Context) {
    return (
      c &&
      this.sameScope(c.scope) &&
      c.tool === 'text.metrics' &&
      c.version === 1 &&
      c.capability === 'text:inspect' &&
      c.action === 'INSPECT' &&
      typeof c.canIssue === 'boolean' &&
      typeof c.canRevoke === 'boolean' &&
      /^[0-9a-f]{64}$/.test(c.fingerprint) &&
      (c.grant === null ||
        (uuid(c.grant.id) &&
          Number.isFinite(Date.parse(c.grant.issuedAt)) &&
          Number.isFinite(Date.parse(c.grant.expiresAt)) &&
          (c.grant.revokedAt === null ||
            Number.isFinite(Date.parse(c.grant.revokedAt)))))
    );
  }
  private denied(e: unknown) {
    return (
      e instanceof HttpErrorResponse && (e.status === 401 || e.status === 403)
    );
  }
  @HostListener('window:focus') focus() {
    if (!this.busy()) void this.load();
  }
  @HostListener('window:beforeunload', ['$event']) unload(
    e: BeforeUnloadEvent,
  ) {
    if (this.pending()) {
      e.preventDefault();
      e.returnValue = '';
    }
  }
  canLeave() {
    return (
      !this.pending() ||
      window.confirm(
        'Lệnh đang chưa xác định kết quả. Rời trang sẽ mất thông tin gửi lại lệnh.',
      )
    );
  }
  async load() {
    const e = ++this.epoch,
      a = this.auth.user()?.id;
    this.clear();
    this.busy.set(true);
    try {
      await this.authorize();
      if (!this.active(e, a)) return;
      const {data} = await firstValueFrom(
        this.http.get<{data: Context}>(this.base()).pipe(timeout(15000)),
      );
      if (!this.active(e, a)) return;
      if (!this.valid(data)) throw new Error('response');
      this.context.set(data);
      this.message.set('Đã kiểm tra quyền hiện tại.');
    } catch (error) {
      if (this.active(e, a)) {
        this.clear();
        if (this.denied(error)) {
          this.pending.set(null);
          this.message.set(
            'Cần quyền ADMIN hiện hành. Nếu đã gửi lệnh, việc mất quyền không chứng minh lệnh thất bại.',
          );
        } else
          this.message.set(
            'Không đọc được quyền hiện tại. Công cụ có thể chưa được bật.',
          );
      }
    } finally {
      if (this.active(e, a)) this.busy.set(false);
    }
  }
  begin(operation: Operation) {
    const c = this.context();
    if (
      this.busy() ||
      this.pending() ||
      !this.confirmed() ||
      !c ||
      !(operation === 'ISSUE' ? c.canIssue : c.canRevoke)
    )
      return;
    this.uncertain = false;
    this.pending.set({
      idempotencyKey: crypto.randomUUID(),
      operation,
      expectedFingerprint: c.fingerprint,
    });
    this.confirmed.set(false);
    void this.send();
  }
  async send() {
    const command = this.pending();
    if (!command || this.busy()) return;
    const e = ++this.epoch,
      a = this.auth.user()?.id;
    let dispatched = false;
    const wasUncertain = this.uncertain;
    this.busy.set(true);
    this.clear();
    try {
      await this.authorize();
      const {data: csrf} = await firstValueFrom(
        this.http
          .get<{data: {headerName: string; token: string}}>('/api/v1/auth/csrf')
          .pipe(timeout(15000)),
      );
      if (!this.active(e, a)) return;
      if (
        csrf?.headerName !== 'X-CSRF-TOKEN' ||
        typeof csrf.token !== 'string' ||
        !csrf.token ||
        csrf.token.length > 4096 ||
        /[\r\n]/.test(csrf.token)
      )
        throw new Error('csrf');
      dispatched = true;
      const {data: r} = await firstValueFrom(
        this.http
          .post<{
            data: Receipt;
          }>(this.base(), command, {
            headers: new HttpHeaders({[csrf.headerName]: csrf.token}),
          })
          .pipe(timeout(15000)),
      );
      if (!this.active(e, a)) return;
      if (
        !r ||
        !this.sameScope(r.scope) ||
        r.idempotencyKey !== command.idempotencyKey ||
        r.operation !== command.operation ||
        !uuid(r.grantId) ||
        !Number.isFinite(Date.parse(r.recordedAt))
      )
        throw new Error('receipt');
      this.pending.set(null);
      this.uncertain = false;
      await this.load();
      if (
        this.sameScope(r.scope) &&
        a === this.auth.user()?.id &&
        !this.destroy.destroyed &&
        this.context()
      ) {
        this.receipt.set(r);
        this.message.set('Đã ghi nhận thao tác.');
      }
    } catch (error) {
      if (this.active(e, a)) {
        if (this.denied(error)) {
          this.pending.set(null);
          this.message.set(
            'Cần quyền ADMIN hiện hành. Lệnh trước đó có thể đã được ghi nhận.',
          );
        } else if (
          !wasUncertain &&
          (!dispatched ||
            (error instanceof HttpErrorResponse &&
              [400, 404, 409].includes(error.status)))
        ) {
          this.pending.set(null);
          this.uncertain = false;
          await this.load();
          if (a === this.auth.user()?.id && this.context())
            this.message.set(
              'Lệnh chưa được chấp nhận. Kiểm tra quyền hiện tại và xác nhận lại.',
            );
        } else {
          this.uncertain = true;
          this.message.set(
            'Chưa xác định được kết quả. Gửi lại sẽ dùng đúng mã lệnh cũ.',
          );
        }
      }
    } finally {
      if (this.active(e, a)) this.busy.set(false);
    }
  }
}

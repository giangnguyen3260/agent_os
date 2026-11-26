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
interface Context {
  runId: string;
  revision: string;
  fingerprint: string;
}
interface Command {
  idempotencyKey: string;
  expectedFingerprint: string;
}
interface Receipt {
  runId: string;
  idempotencyKey: string;
  sourceRevision: string;
  resultingRevision: string;
}
@Component({
  standalone: true,
  imports: [RouterLink],
  template: `<main>
    <a [routerLink]="['/workspace', org, 'execution-runs', run]"
      >Về chi tiết Run</a
    >
    <h1>Chuẩn bị lại Run chưa gửi provider</h1>
    <p>
      Chỉ áp dụng cho thất bại có bằng chứng worker chưa gửi provider. Không xử
      lý UNKNOWN, không tự chạy lại, không tự cấp quyền Tool.
    </p>
    <p>
      Backend kiểm tra lại graph, phụ thuộc, owner, trạng thái cha và điều khiển
      Mission khi xác nhận. Run sẵn sàng vẫn cần xem trước và Start riêng.
    </p>
    <p role="status">{{ message() }}</p>
    @if (context(); as c) {
      <section>
        <h2>Run được đối chiếu</h2>
        <p>{{ c.runId }} · Phiên bản {{ c.revision }}</p>
        @if (!pending() && !receipt()) {
          <label
            ><input
              type="checkbox"
              [checked]="confirmed()"
              (change)="confirmed.set($any($event.target).checked)"
              [disabled]="busy()"
            />
            Tôi xác nhận chỉ đưa Run này về trạng thái sẵn sàng, chưa khởi
            chạy.</label
          ><button (click)="prepare()" [disabled]="busy() || !confirmed()">
            Xác nhận chuẩn bị lại
          </button>
        }
      </section>
    }
    @if (pending()) {
      <p>Lệnh chưa xác định kết quả. Giữ nguyên mã lệnh; không tạo lệnh mới.</p>
      <p>Mã lệnh: {{ pending()?.idempotencyKey }}</p>
      <button (click)="send()" [disabled]="busy()">
        Gửi lại đúng lệnh chuẩn bị
      </button>
    }
    @if (receipt(); as r) {
      <section>
        <h2>Biên nhận chuẩn bị lại</h2>
        <p>
          Phiên bản nguồn {{ r.sourceRevision }} → {{ r.resultingRevision }}
        </p>
        <p>
          Đã ghi nhận thao tác chuẩn bị. Về Run để kiểm tra trạng thái hiện tại;
          biên nhận không tự cấp quyền Start.
        </p>
      </section>
    }
    <section>
      <h2>Tra cứu biên nhận đã lưu</h2>
      <label
        >Mã lệnh chuẩn bị
        <input
          [value]="lookupKey()"
          (input)="lookupKey.set($any($event.target).value)"
          [disabled]="busy()"
          autocomplete="off"
        />
      </label>
      <button (click)="lookup()" [disabled]="busy()">Tra cứu biên nhận</button>
      <p>{{ lookupMessage() }}</p>
      @if (historical(); as h) {
        <h3>Biên nhận lịch sử</h3>
        <p>Mã lệnh {{ h.idempotencyKey }}</p>
        <p>Phiên bản {{ h.sourceRevision }} → {{ h.resultingRevision }}</p>
        <p>
          Biên nhận không xác nhận trạng thái Run hiện tại và không cấp quyền
          Start.
        </p>
      }
    </section>
    <button (click)="load()" [disabled]="busy()">
      Kiểm tra điều kiện hiện tại
    </button>
  </main>`,
  styles: [
    `
      main {
        max-width: 900px;
        margin: 24px auto;
        padding: 24px;
        color: #173b32;
      }
      p,
      a {
        overflow-wrap: anywhere;
      }
      section {
        border: 1px solid #c5ded3;
        padding: 16px;
      }
      label {
        display: block;
      }
      button {
        margin: 12px 0;
        padding: 12px;
      }
    `,
  ],
})
export class UnsentRunRecoveryComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  org = '';
  run = '';
  readonly context = signal<Context | null>(null);
  readonly confirmed = signal(false);
  readonly receipt = signal<Receipt | null>(null);
  readonly pending = signal<Command | null>(null);
  readonly busy = signal(false);
  readonly message = signal('');
  readonly lookupKey = signal('');
  readonly lookupMessage = signal('');
  readonly historical = signal<Receipt | null>(null);
  private epoch = 0;
  private captured: Context | null = null;
  private uncertain = false;
  constructor() {
    let account = this.auth.user()?.id;
    effect(() => {
      const next = this.auth.user()?.id;
      if (next !== account) {
        account = next;
        this.epoch++;
        this.erase();
        this.pending.set(null);
        this.captured = null;
        this.busy.set(false);
        this.message.set('Phiên đăng nhập đã thay đổi.');
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroy)).subscribe(p => {
      this.epoch++;
      this.erase();
      this.pending.set(null);
      this.captured = null;
      this.org = p.get('id') || '';
      this.run = p.get('runId') || '';
      void this.load();
    });
  }
  private erase() {
    this.lookupKey.set('');
    this.lookupMessage.set('');
    this.historical.set(null);
    this.context.set(null);
    this.confirmed.set(false);
    this.receipt.set(null);
  }
  private active(e: number, a: string | undefined) {
    return (
      e === this.epoch &&
      !!a &&
      a === this.auth.user()?.id &&
      !this.destroy.destroyed
    );
  }
  private base() {
    return `/api/v1/organizations/${this.org}/execution-runs/${this.run}/unsent-recovery`;
  }
  private denied(error: unknown) {
    return (
      error instanceof HttpErrorResponse && [401, 403].includes(error.status)
    );
  }
  private async authorize() {
    if (!uuid(this.org) || !uuid(this.run)) throw new Error('scope');
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
  private valid(c: Context) {
    return (
      c &&
      c.runId === this.run &&
      typeof c.revision === 'string' &&
      /^[1-9][0-9]{0,18}$/.test(c.revision) &&
      BigInt(c.revision) >= 2n &&
      BigInt(c.revision) <= 9223372036854775802n &&
      typeof c.fingerprint === 'string' &&
      /^[0-9a-f]{64}$/.test(c.fingerprint)
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
        'Lệnh chuẩn bị chưa rõ kết quả. Rời trang sẽ mất thông tin gửi lại lệnh.',
      )
    );
  }
  async lookup() {
    if (this.busy()) return;
    this.historical.set(null);
    this.lookupMessage.set('');
    const key = this.lookupKey().trim();
    if (!uuid(key)) {
      this.lookupMessage.set('Mã lệnh phải là UUID hợp lệ.');
      return;
    }
    const e = ++this.epoch,
      a = this.auth.user()?.id;
    this.context.set(null);
    this.confirmed.set(false);
    this.receipt.set(null);
    this.busy.set(true);
    try {
      await this.authorize();
      if (!this.active(e, a)) return;
      const {data: r} = await firstValueFrom(
        this.http
          .get<{data: Receipt | null}>(`${this.base()}/receipts/${key}`)
          .pipe(timeout(15000)),
      );
      if (!this.active(e, a)) return;
      if (r === null) {
        this.lookupMessage.set(
          'Không tìm thấy biên nhận trong phạm vi hiện tại. Kết quả lệnh chưa được xác nhận.',
        );
        return;
      }
      if (
        !r ||
        r.runId !== this.run ||
        r.idempotencyKey !== key ||
        typeof r.sourceRevision !== 'string' ||
        !/^[1-9][0-9]{0,18}$/.test(r.sourceRevision) ||
        BigInt(r.sourceRevision) < 2n ||
        BigInt(r.sourceRevision) > 9223372036854775802n ||
        r.resultingRevision !== (BigInt(r.sourceRevision) + 1n).toString()
      )
        throw new Error('receipt');
      this.historical.set(r);
    } catch (error) {
      if (this.active(e, a)) {
        this.lookupMessage.set(
          'Không xác nhận được biên nhận. Kiểm tra quyền ADMIN và mã lệnh.',
        );
        if (this.denied(error)) {
          this.pending.set(null);
          this.captured = null;
        }
      }
    } finally {
      if (this.active(e, a)) this.busy.set(false);
    }
  }
  async load() {
    const e = ++this.epoch,
      a = this.auth.user()?.id;
    this.erase();
    this.busy.set(true);
    try {
      await this.authorize();
      if (!this.active(e, a)) return;
      const {data} = await firstValueFrom(
        this.http.get<{data: Context}>(this.base()).pipe(timeout(15000)),
      );
      if (!this.active(e, a)) return;
      if (!this.valid(data)) throw new Error('context');
      this.context.set(data);
      this.message.set(
        'Đã đối chiếu bằng chứng. Cần xác nhận và kiểm tra lại trước khi ghi nhận.',
      );
    } catch (error) {
      if (this.active(e, a)) {
        if (this.denied(error)) {
          this.pending.set(null);
          this.captured = null;
          this.message.set(
            'Cần quyền ADMIN hiện hành. Lệnh trước đó có thể đã được ghi nhận.',
          );
        } else
          this.message.set(
            'Không xác nhận được điều kiện chuẩn bị lại. Kiểm tra bằng chứng, UNKNOWN và trạng thái Mission.',
          );
      }
    } finally {
      if (this.active(e, a)) this.busy.set(false);
    }
  }
  prepare() {
    const c = this.context();
    if (
      !c ||
      !this.confirmed() ||
      this.busy() ||
      this.pending() ||
      this.receipt()
    )
      return;
    this.captured = {...c};
    this.uncertain = false;
    this.pending.set({
      idempotencyKey: crypto.randomUUID(),
      expectedFingerprint: c.fingerprint,
    });
    this.confirmed.set(false);
    void this.send();
  }
  async send() {
    const command = this.pending(),
      captured = this.captured;
    if (!command || !captured || this.busy()) return;
    const e = ++this.epoch,
      a = this.auth.user()?.id;
    let sent = false;
    const wasUncertain = this.uncertain;
    this.erase();
    this.busy.set(true);
    try {
      await this.authorize();
      if (!this.active(e, a)) return;
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
      sent = true;
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
        r.runId !== this.run ||
        r.idempotencyKey !== command.idempotencyKey ||
        r.sourceRevision !== captured.revision ||
        r.resultingRevision !== (BigInt(captured.revision) + 1n).toString()
      )
        throw new Error('receipt');
      this.pending.set(null);
      this.captured = null;
      this.receipt.set(r);
      this.message.set('Đã ghi nhận chuẩn bị lại. Chưa có lệnh Start mới.');
    } catch (error) {
      if (this.active(e, a)) {
        if (this.denied(error)) {
          this.pending.set(null);
          this.captured = null;
          this.message.set(
            'Cần quyền ADMIN hiện hành. Lệnh trước đó có thể đã được ghi nhận.',
          );
        } else if (
          !wasUncertain &&
          (!sent ||
            (error instanceof HttpErrorResponse &&
              [400, 404, 409].includes(error.status)))
        ) {
          this.pending.set(null);
          this.captured = null;
          await this.load();
          if (this.auth.user()?.id === a && this.context())
            this.message.set(
              'Lệnh chưa được chấp nhận. Kiểm tra và xác nhận lại.',
            );
        } else {
          this.uncertain = true;
          this.message.set(
            'Chưa xác định được kết quả. Chỉ gửi lại đúng lệnh đã giữ.',
          );
        }
      }
    } finally {
      if (this.active(e, a)) this.busy.set(false);
    }
  }
}

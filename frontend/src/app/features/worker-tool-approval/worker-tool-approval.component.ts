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
interface Preview {
  runId: string;
  revision: string;
  grantId: string;
  text: string;
  inputSha256: string;
  fingerprint: string;
}
interface Command {
  idempotencyKey: string;
  expectedFingerprint: string;
}
@Component({
  standalone: true,
  imports: [RouterLink],
  template: `<main>
    <a [routerLink]="['/workspace', org, 'execution-runs', run]"
      >Về chi tiết Run</a
    >
    <h1>Duyệt text.metrics trước khi chạy</h1>
    <p>
      Tool phân tích đúng mô tả Task dưới đây, tối đa 8 KiB. Không gửi ra dịch
      vụ ngoài. Quyền Tool và lease được kiểm tra lại khi worker thực thi.
    </p>
    <p>
      Giữ tạm 1 microUSD để kiểm tra ngân sách và năng lực; chi phí Tool thực tế
      là 0. Request model được giữ nguyên.
    </p>
    <p role="status">{{ message() }}</p>
    @if (preview(); as p) {
      <section>
        <h2>Nội dung sẽ phân tích</h2>
        <p>Run: {{ p.runId }} · Phiên bản {{ p.revision }}</p>
        <p>Quyền Tool: {{ p.grantId }}</p>
        <pre aria-label="Nội dung Tool">{{ p.text }}</pre>
        <p>SHA đầu vào: {{ p.inputSha256 }}</p>
        @if (!pending() && !saved()) {
          <label
            ><input
              type="checkbox"
              [checked]="confirmed()"
              (change)="confirmed.set($any($event.target).checked)"
              [disabled]="busy()"
            />
            Tôi duyệt text.metrics v1 cho đúng nội dung và Run này.</label
          ><button (click)="approve()" [disabled]="busy() || !confirmed()">
            Duyệt Tool
          </button>
        }
      </section>
    }
    @if (pending()) {
      <p>
        Lệnh duyệt chưa xác định kết quả. Giữ nguyên mã lệnh; không Start trước
        khi xác minh.
      </p>
      <button (click)="send()" [disabled]="busy()">
        Gửi lại đúng lệnh duyệt
      </button>
    }
    @if (saved()) {
      <p>
        Đã ghi nhận duyệt. Run chưa được khởi chạy; quyền hiện hành sẽ được kiểm
        tra lại.
      </p>
      <a [routerLink]="['/workspace', org, 'execution-runs', run, 'start']"
        >Xem trước và Start Run</a
      >
    }
    <button (click)="load()" [disabled]="busy()">Kiểm tra lại nội dung</button>
  </main>`,
  styles: [
    `
      main {
        max-width: 900px;
        margin: 24px auto;
        padding: 24px;
        color: #173b32;
      }
      pre {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
      }
      p,
      a {
        overflow-wrap: anywhere;
      }
      section {
        border: 1px solid #c5ded3;
        padding: 16px;
      }
      button {
        padding: 12px;
        margin: 12px 0;
      }
      label {
        display: block;
      }
    `,
  ],
})
export class WorkerToolApprovalComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  org = '';
  run = '';
  readonly preview = signal<Preview | null>(null);
  readonly confirmed = signal(false);
  readonly busy = signal(false);
  readonly saved = signal(false);
  readonly pending = signal<Command | null>(null);
  readonly message = signal('');
  private captured: Preview | null = null;
  private acceptedFingerprint: string | null = null;
  private epoch = 0;
  private uncertain = false;
  constructor() {
    let account = this.auth.user()?.id;
    effect(() => {
      const a = this.auth.user()?.id;
      if (a !== account) {
        account = a;
        this.epoch++;
        this.erase();
        this.pending.set(null);
        this.captured = null;
        this.acceptedFingerprint = null;
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
    this.preview.set(null);
    this.confirmed.set(false);
    this.saved.set(false);
  }
  private base() {
    return `/api/v1/organizations/${this.org}/execution-runs/${this.run}/text-tool-approval`;
  }
  private active(e: number, a: string | undefined) {
    return (
      e === this.epoch &&
      !!a &&
      a === this.auth.user()?.id &&
      !this.destroy.destroyed
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
  private async valid(p: Preview) {
    if (
      !p ||
      p.runId !== this.run ||
      !uuid(p.grantId) ||
      typeof p.revision !== 'string' ||
      !/^(0|[1-9][0-9]{0,18})$/.test(p.revision) ||
      BigInt(p.revision) > 9223372036854775807n ||
      typeof p.text !== 'string' ||
      p.text.length > 8192 ||
      Array.from(p.text).some(
        c =>
          c.length === 1 &&
          c.charCodeAt(0) >= 0xd800 &&
          c.charCodeAt(0) <= 0xdfff,
      ) ||
      !['inputSha256', 'fingerprint'].every(
        k =>
          typeof p[k as keyof Preview] === 'string' &&
          /^[0-9a-f]{64}$/.test(p[k as keyof Preview]),
      )
    )
      return false;
    const bytes = new TextEncoder().encode(p.text);
    if (bytes.length > 8192) return false;
    const sha = Array.from(
      new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
      v => v.toString(16).padStart(2, '0'),
    ).join('');
    return sha === p.inputSha256;
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
        'Kết quả duyệt chưa xác định. Rời trang sẽ mất thông tin gửi lại lệnh.',
      )
    );
  }
  private denied(e: unknown) {
    return e instanceof HttpErrorResponse && [401, 403].includes(e.status);
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
        this.http.get<{data: Preview}>(this.base()).pipe(timeout(15000)),
      );
      const valid = await this.valid(data);
      if (!this.active(e, a)) return;
      if (!valid) throw new Error('preview');
      this.preview.set(data);
      this.saved.set(this.acceptedFingerprint === data.fingerprint);
      this.message.set('Đã kiểm tra nội dung hiện tại.');
    } catch (error) {
      if (this.active(e, a)) {
        if (this.denied(error)) {
          this.pending.set(null);
          this.captured = null;
          this.message.set(
            'Cần quyền ADMIN hiện hành. Lệnh đã gửi có thể đã được ghi nhận.',
          );
        } else
          this.message.set(
            'Không đọc được bản duyệt. Kiểm tra quyền Tool và trạng thái Run.',
          );
      }
    } finally {
      if (this.active(e, a)) this.busy.set(false);
    }
  }
  approve() {
    const p = this.preview();
    if (!p || !this.confirmed() || this.pending() || this.busy()) return;
    this.captured = {...p};
    this.uncertain = false;
    this.pending.set({
      idempotencyKey: crypto.randomUUID(),
      expectedFingerprint: p.fingerprint,
    });
    this.confirmed.set(false);
    void this.send();
  }
  async send() {
    const c = this.pending(),
      captured = this.captured;
    if (!c || !captured || this.busy()) return;
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
      const {data} = await firstValueFrom(
        this.http
          .post<{
            data: Preview;
          }>(this.base(), c, {
            headers: new HttpHeaders({[csrf.headerName]: csrf.token}),
          })
          .pipe(timeout(15000)),
      );
      const valid = await this.valid(data);
      if (!this.active(e, a)) return;
      if (
        !valid ||
        Object.keys(captured).some(
          k => data[k as keyof Preview] !== captured[k as keyof Preview],
        )
      )
        throw new Error('receipt');
      this.pending.set(null);
      this.captured = null;
      this.preview.set(data);
      this.acceptedFingerprint = data.fingerprint;
      this.saved.set(true);
      this.message.set('Đã duyệt Tool.');
    } catch (error) {
      if (this.active(e, a)) {
        if (this.denied(error)) {
          this.pending.set(null);
          this.captured = null;
          this.message.set(
            'Cần quyền ADMIN hiện hành. Lệnh đã gửi có thể đã được ghi nhận.',
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
          if (this.auth.user()?.id === a && this.preview())
            this.message.set(
              'Lệnh chưa được chấp nhận. Kiểm tra lại và xác nhận mới.',
            );
        } else {
          this.uncertain = true;
          this.message.set(
            'Chưa xác định kết quả. Chỉ gửi lại đúng lệnh đã giữ.',
          );
        }
      }
    } finally {
      if (this.active(e, a)) this.busy.set(false);
    }
  }
}

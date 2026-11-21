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
interface Preflight {
  runId?: string;
  taskId?: string;
  revision: string;
  connectionId: string;
  ownerAgentId: string;
  requestPreview: string;
  model: string;
  maxOutputTokens: number;
  maximumMicrousd: string;
  fingerprint: string;
  dependencyContextSha256?: string | null;
  dependencyCount?: number;
}
interface Pending {
  expectedRevision: string;
  idempotencyKey: string;
  fingerprint: string;
}
@Component({
  standalone: true,
  imports: [RouterLink],
  styleUrl: '../execution-review/execution-review.component.css',
  template: ` <main>
    <h1>Khởi chạy Task bằng OpenAI</h1>
    @if (run) {
      <p>
        Ngữ cảnh phụ thuộc, nếu được chọn khi tiếp nhận graph, phải vừa giới hạn
        8 KiB; tổng nội dung message tối đa 16 KiB. Context vượt giới hạn sẽ bị
        chặn, không tự cắt hoặc bỏ output.
      </p>
    }
    @if (run) {
      <a [routerLink]="['/workspace', org, 'execution-runs', run]"
        >Về lần chạy / kiểm tra trạng thái</a
      >
    } @else {
      <a
        [routerLink]="[
          '/workspace',
          org,
          'teams',
          team,
          'missions',
          mission,
          'tasks',
          task,
        ]"
        >Về Task</a
      >
      <p>
        Start chấp nhận snapshot cho một Task không có phụ thuộc, cho phép tối
        đa một lần làm lại.
      </p>
    }
    <p role="status">{{ busy() ? 'Đang xử lý…' : message() }}</p>
    @if (saved()) {
      <p>
        Đã ghi nhận lệnh Start. Worker sẽ kiểm tra điều kiện hiện hành trước khi
        gọi model. Mở lần chạy để xem trạng thái; chưa có kết quả được xác nhận
        ở bước này.
      </p>
    } @else if (pending()) {
      <p>
        Kết quả gửi Start chưa xác định. Chỉ gửi lại đúng yêu cầu đã giữ hoặc mở
        lần chạy để kiểm tra. Không tạo yêu cầu thay thế.
      </p>
      <button (click)="send()" [disabled]="busy()">
        Gửi lại đúng lệnh Start
      </button>
    } @else {
      <button (click)="load()" [disabled]="busy()">Tải lại điều kiện</button>
      @if (data(); as p) {
        <dl>
          <dt>Model</dt>
          <dd>{{ p.model }}</dd>
          <dt>Connection</dt>
          <dd>{{ p.connectionId }}</dd>
          <dt>Agent thực thi</dt>
          <dd>{{ p.ownerAgentId }}</dd>
          <dt>Giới hạn output token</dt>
          <dd>{{ p.maxOutputTokens }}</dd>
          <dt>Ngân sách giữ tối đa (micro-USD)</dt>
          <dd>{{ p.maximumMicrousd }}</dd>
        </dl>
        @if (p.dependencyContextSha256) {
          <p>
            Đầu vào đã ghim từ {{ p.dependencyCount }} Task tiên quyết. Dữ liệu
            tham khảo được hiển thị trong request bên dưới; giới hạn ngữ cảnh 8
            KiB, không tự cắt nội dung.
          </p>
          <p>
            Mã ngữ cảnh: <code>{{ p.dependencyContextSha256 }}</code>
          </p>
        }
        <h2>Request sẽ gửi tới model</h2>
        <pre tabindex="0" aria-label="Request đề xuất">{{
          p.requestPreview
        }}</pre>
        <p>
          Chạy một Task văn bản, không gọi công cụ. Chi phí được hạch toán theo
          biểu giá do quản trị hệ thống cấu hình. Kết quả sẽ chờ người duyệt.
        </p>
        <button (click)="start()" [disabled]="busy()">
          Xác nhận Start Task
        </button>
      }
    }
  </main>`,
})
export class ExecutionStartComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private generation = 0;
  private account = this.auth.user()?.id;
  org = '';
  run = '';
  team = '';
  mission = '';
  task = '';
  readonly data = signal<Preflight | null>(null);
  readonly pending = signal<Pending | null>(null);
  readonly busy = signal(false);
  readonly saved = signal(false);
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
      this.run = p.get('runId') || '';
      this.team = p.get('teamId') || '';
      this.mission = p.get('missionId') || '';
      this.task = p.get('taskId') || '';
      void this.load();
    });
    this.destroy.onDestroy(() => this.clear());
  }
  private clear() {
    this.generation++;
    this.data.set(null);
    this.pending.set(null);
    this.saved.set(false);
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
    return this.task
      ? `/api/v1/organizations/${this.org}/teams/${this.team}/missions/${this.mission}/tasks/${this.task}/start`
      : `/api/v1/organizations/${this.org}/execution-runs/${this.run}/start`;
  }
  private async authorized() {
    const w = await this.auth.getWorkspace(this.org);
    if (w?.id !== this.org || w.role !== 'ADMIN')
      throw new HttpErrorResponse({status: 403});
  }
  @HostListener('window:focus') focus() {
    if (!this.busy() && !this.pending() && !this.saved()) void this.load();
  }
  @HostListener('document:visibilitychange') visible() {
    if (document.visibilityState === 'visible') this.focus();
  }
  async load() {
    if (this.busy() || this.pending()) return;
    this.data.set(null);
    const g = ++this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    this.message.set('');
    try {
      if (
        !uuid(this.org) ||
        (this.task
          ? !uuid(this.task) || !uuid(this.team) || !uuid(this.mission)
          : !uuid(this.run))
      )
        throw new Error('invalid');
      await this.authorized();
      if (!this.active(g, a)) return;
      const {data} = await firstValueFrom(
        this.http.get<{data: Preflight}>(this.base()).pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      if (
        !data ||
        (this.task ? data.taskId !== this.task : data.runId !== this.run) ||
        !uuid(data.connectionId) ||
        !uuid(data.ownerAgentId) ||
        typeof data.requestPreview !== 'string' ||
        new TextEncoder().encode(data.requestPreview).length > 32768 ||
        !data.requestPreview.trim() ||
        typeof data.revision !== 'string' ||
        !/^(0|[1-9]\d{0,18})$/.test(data.revision) ||
        ((data.dependencyContextSha256 !== undefined ||
          data.dependencyCount !== undefined) &&
          !(
            (data.dependencyContextSha256 === null &&
              data.dependencyCount === 0) ||
            (typeof data.dependencyContextSha256 === 'string' &&
              /^[0-9a-f]{64}$/.test(data.dependencyContextSha256) &&
              Number.isInteger(data.dependencyCount) &&
              data.dependencyCount! >= 0 &&
              data.dependencyCount! <= 200)
          )) ||
        typeof data.fingerprint !== 'string' ||
        !/^[0-9a-f]{64}$/.test(data.fingerprint) ||
        typeof data.model !== 'string' ||
        !data.model.trim() ||
        !Number.isSafeInteger(data.maxOutputTokens) ||
        data.maxOutputTokens < 1 ||
        data.maxOutputTokens > 4096 ||
        typeof data.maximumMicrousd !== 'string' ||
        !/^[1-9]\d{0,18}$/.test(data.maximumMicrousd)
      )
        throw new Error('invalid');
      this.data.set(data);
    } catch (e) {
      if (this.active(g, a))
        this.message.set(
          e instanceof HttpErrorResponse && e.status === 404
            ? 'Runtime OpenAI chưa được bật hoặc Run không khả dụng.'
            : 'Không xác nhận được điều kiện Start. Cần quyền ADMIN và Run READY hợp lệ; hãy tải lại.',
        );
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
  }
  start() {
    const p = this.data();
    if (!p || this.busy() || this.pending()) return;
    this.pending.set(
      Object.freeze({
        expectedRevision: p.revision,
        idempotencyKey: crypto.randomUUID(),
        fingerprint: p.fingerprint,
      }),
    );
    this.data.set(null);
    void this.send();
  }
  async send() {
    const p = this.pending();
    if (!p || this.busy()) return;
    const g = this.generation,
      a = this.auth.user()?.id;
    this.busy.set(true);
    try {
      await this.authorized();
      if (!this.active(g, a)) return;
      const csrf = await firstValueFrom(
        this.http
          .get<{data: {headerName: string; token: string}}>('/api/v1/auth/csrf')
          .pipe(timeout(15000)),
      );
      if (!this.active(g, a)) return;
      const r = await firstValueFrom(
        this.http
          .post<{
            data: {dispatchId: string; runId: string; sourceRevision: string};
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
        !uuid(r?.data?.dispatchId) ||
        (!this.task && r.data.runId !== this.run) ||
        r.data.sourceRevision !== p.expectedRevision
      )
        throw new Error('invalid');
      this.run = r.data.runId;
      this.pending.set(null);
      this.saved.set(true);
      this.message.set('Đã ghi nhận Start.');
    } catch (e) {
      if (this.active(g, a)) {
        if (e instanceof HttpErrorResponse && [401, 403].includes(e.status)) {
          this.clear();
          this.message.set(
            'Quyền Start không còn hiệu lực. Mở lần chạy để kiểm tra trạng thái.',
          );
        } else this.message.set('Chưa xác nhận được kết quả Start.');
      }
    } finally {
      if (this.active(g, a)) this.busy.set(false);
    }
  }
}

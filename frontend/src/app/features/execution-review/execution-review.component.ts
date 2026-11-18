import {HttpClient, HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  HostListener,
  effect,
  inject,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {firstValueFrom, timeout} from 'rxjs';
import {AuthFailure, AuthService} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';

interface Review {
  attemptId: string;
  revision: number;
  outputSha256: string;
  reworkAvailable: boolean;
}
interface Output extends Review {
  content: string;
}
interface Envelope<T> {
  data: T;
}
type Decision = 'ACCEPT' | 'REWORK';

@Component({
  selector: 'app-execution-review',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './execution-review.component.html',
  styleUrl: './execution-review.component.css',
})
export class ExecutionReviewComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private generation = 0;
  private account = this.auth.user()?.id;
  workspace = '';
  private run = '';
  readonly review = signal<Review | null>(null);
  readonly content = signal<string | null>(null);
  readonly busy = signal(false);
  readonly message = signal('');
  readonly confirmation = signal<Decision | null>(null);
  reason = '';

  constructor() {
    effect(() => {
      const account = this.auth.user()?.id;
      if (account !== this.account) {
        this.account = account;
        this.clear();
        this.message.set('Phiên đăng nhập đã thay đổi. Hãy tải lại.');
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.clear();
      this.workspace = params.get('id') || '';
      this.run = params.get('runId') || '';
      void this.load();
    });
    this.destroy.onDestroy(() => this.clear());
  }

  private clear(): number {
    this.generation++;
    this.review.set(null);
    this.content.set(null);
    this.confirmation.set(null);
    this.reason = '';
    this.busy.set(false);
    this.message.set('');
    return this.generation;
  }

  private current(generation: number, account: string | undefined): boolean {
    return (
      generation === this.generation &&
      !this.destroy.destroyed &&
      !!account &&
      account === this.auth.user()?.id
    );
  }

  private base(): string {
    return (
      '/api/v1/organizations/' +
      encodeURIComponent(this.workspace) +
      '/execution-runs/' +
      encodeURIComponent(this.run) +
      '/review'
    );
  }

  @HostListener('window:focus')
  focus(): void {
    if (!this.busy()) void this.load();
  }

  @HostListener('document:visibilitychange')
  visible(): void {
    if (document.visibilityState === 'visible') this.focus();
  }

  async load(): Promise<void> {
    if (this.busy()) return;
    const generation = this.clear();
    const account = this.auth.user()?.id;
    this.busy.set(true);
    const base = this.base();
    try {
      if (!uuid(this.workspace) || !uuid(this.run)) throw new Error('invalid');
      const workspace = await this.auth.getWorkspace(this.workspace);
      if (!this.current(generation, account)) return;
      if (workspace?.id !== this.workspace || workspace.role !== 'ADMIN')
        throw new HttpErrorResponse({status: 403});
      const {data} = await firstValueFrom(
        this.http.get<Envelope<Review>>(base).pipe(timeout(15000)),
      );
      if (!this.current(generation, account)) return;
      if (
        !data ||
        !uuid(data.attemptId) ||
        !Number.isSafeInteger(data.revision) ||
        data.revision <= 0 ||
        typeof data.outputSha256 !== 'string' ||
        !/^[0-9a-f]{64}$/.test(data.outputSha256) ||
        typeof data.reworkAvailable !== 'boolean'
      )
        throw new Error('invalid');
      const response = await firstValueFrom(
        this.http
          .get<Envelope<Output>>(base + '/output', {
            params: {
              attemptId: data.attemptId,
              expectedRevision: data.revision,
            },
          })
          .pipe(timeout(15000)),
      );
      if (!this.current(generation, account)) return;
      const output = response.data;
      if (
        !output ||
        output.attemptId !== data.attemptId ||
        output.revision !== data.revision ||
        output.outputSha256 !== data.outputSha256 ||
        typeof output.content !== 'string' ||
        new TextEncoder().encode(output.content).length > 262144
      )
        throw new Error('invalid');
      this.review.set(data);
      this.content.set(output.content);
    } catch (error) {
      if (this.current(generation, account)) this.failure(error);
    } finally {
      if (this.current(generation, account)) this.busy.set(false);
    }
  }

  prepare(decision: Decision): void {
    if (!this.review() || this.content() === null || this.busy()) return;
    if (
      decision === 'REWORK' &&
      (!this.review()?.reworkAvailable || !this.reason.trim())
    )
      return;
    this.confirmation.set(decision);
  }

  async submit(): Promise<void> {
    const review = this.review();
    const decision = this.confirmation();
    if (!review || !decision || this.busy()) return;
    const generation = this.generation;
    const account = this.auth.user()?.id;
    const base = this.base();
    const body = {
      attemptId: review.attemptId,
      expectedRevision: review.revision,
      outputSha256: review.outputSha256,
      decision,
      reason: decision === 'REWORK' ? this.reason.trim() : null,
    };
    this.busy.set(true);
    try {
      const workspace = await this.auth.getWorkspace(this.workspace);
      if (!this.current(generation, account)) return;
      if (workspace?.id !== this.workspace || workspace.role !== 'ADMIN')
        throw new HttpErrorResponse({status: 403});
      const csrf = await firstValueFrom(
        this.http
          .get<
            Envelope<{headerName: string; token: string}>
          >('/api/v1/auth/csrf')
          .pipe(timeout(15000)),
      );
      if (!this.current(generation, account)) return;
      await firstValueFrom(
        this.http
          .post(base + '/decision', body, {
            headers: new HttpHeaders().set(
              csrf.data.headerName,
              csrf.data.token,
            ),
          })
          .pipe(timeout(15000)),
      );
      if (!this.current(generation, account)) return;
      this.clear();
      this.message.set(
        decision === 'ACCEPT' ? 'Đã duyệt kết quả.' : 'Đã yêu cầu làm lại.',
      );
    } catch (error) {
      if (this.current(generation, account)) {
        this.clear();
        this.failure(error);
      }
    } finally {
      if (this.current(generation, account)) this.busy.set(false);
    }
  }

  private failure(error: unknown): void {
    const status =
      error instanceof HttpErrorResponse
        ? error.status
        : error instanceof AuthFailure && error.kind === 'unauthorized'
          ? 403
          : 0;
    this.message.set(
      status === 403 || status === 401
        ? 'Bạn cần quyền ADMIN hiện hành để xem và duyệt kết quả.'
        : status === 404
          ? 'Chưa có nội dung kết quả đang chờ duyệt.'
          : status === 409
            ? 'Phiên bản review đã thay đổi. Hãy tải lại.'
            : 'Chưa xác nhận được kết quả yêu cầu. Hãy tải lại để kiểm tra trạng thái hiện tại.',
    );
  }
}

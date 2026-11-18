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
interface Run {
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

import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import {firstValueFrom, Subject, takeUntil, timeout} from 'rxjs';
import {AuthService} from '../../core/auth/auth.service';
import {csrfHeader} from '../provider-connections/provider-create-wire';
import {
  confirmedModelGrant,
  ModelGrant,
  ModelGrantRequest,
  modelGrant,
} from './agent-model-grant-wire';

interface Scope {
  organization: string;
  agent: string;
  account: string;
  bindingRevision: number;
  closed: Subject<void>;
}

interface Attempt {
  key: string;
  sends: number;
}

@Component({
  selector: 'app-agent-model-grant',
  templateUrl: './agent-model-grant.component.html',
  styleUrl: './agent-model-grant.component.css',
})
export class AgentModelGrantComponent {
  readonly organizationId = input.required<string>();
  readonly agentId = input.required<string>();
  readonly bindingRevision = input.required<number>();
  readonly modelId = input.required<string | null>();
  readonly blocked = input.required<boolean>();
  readonly refreshBinding = output<void>();
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private scope: Scope | null = null;
  readonly state = signal<ModelGrant | null>(null);
  readonly review = signal<ModelGrantRequest | null>(null);
  readonly attempt = signal<Attempt | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly needsRefresh = signal(false);
  readonly labels: Record<ModelGrant['state'], string> = {
    ABSENT: 'Chưa cấp quyền',
    GRANTED: 'Đã cấp quyền',
    REVOKED: 'Đã thu hồi quyền',
    STALE: 'Quyền đã cấp thuộc lựa chọn model trước',
    UNAVAILABLE: 'Kết nối của quyền đã cấp hiện không khả dụng',
  };

  constructor() {
    effect(onCleanup => {
      const organization = this.organizationId();
      const agent = this.agentId();
      const bindingRevision = this.bindingRevision();
      const account = this.auth.user()?.id;
      if (!account) return;
      const scope = {
        organization,
        agent,
        account,
        bindingRevision,
        closed: new Subject<void>(),
      };
      this.scope = scope;
      untracked(() => void this.refresh());
      onCleanup(() => {
        this.scope = null;
        scope.closed.next();
        scope.closed.complete();
        this.clear();
      });
    });
  }

  dirty(): boolean {
    return !!(this.review() || this.attempt());
  }

  async refresh(): Promise<void> {
    const scope = this.scope;
    if (!scope || !this.current(scope) || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const state = await this.read(scope);
      if (!this.current(scope)) return;
      this.setCurrent(scope, state);
      this.cancel();
    } catch (error) {
      if (this.current(scope) && !this.hideFor(error)) {
        this.needsRefresh.set(true);
        this.error.set('Không thể tải quyền dùng model. Vui lòng thử lại.');
      }
    } finally {
      if (this.current(scope)) this.busy.set(false);
    }
  }

  reload(): void {
    this.refreshBinding.emit();
    void this.refresh();
  }

  prepare(enabled: boolean): void {
    const grant = this.state();
    if (
      !grant ||
      this.blocked() ||
      this.busy() ||
      this.needsRefresh() ||
      this.review() ||
      grant.bindingRevision !== this.bindingRevision() ||
      (enabled && !this.modelId()) ||
      !(enabled ? grant.canGrant : grant.canRevoke)
    )
      return;
    this.notice.set('');
    this.error.set('');
    this.review.set({
      expectedRevision: grant.revision,
      expectedBindingRevision: grant.bindingRevision,
      enabled,
    });
  }

  cancel(): void {
    this.review.set(null);
    this.attempt.set(null);
  }

  async confirm(): Promise<void> {
    const scope = this.scope;
    const review = this.review();
    const prior = this.attempt();
    if (
      !scope ||
      !this.current(scope) ||
      !review ||
      this.blocked() ||
      this.busy() ||
      this.needsRefresh() ||
      (prior && prior.sends >= 2)
    )
      return;
    const attempt = prior
      ? {...prior, sends: prior.sends + 1}
      : {key: crypto.randomUUID(), sends: 1};
    this.attempt.set(attempt);
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const token = await firstValueFrom(
        this.http
          .get<unknown>('/api/v1/auth/csrf')
          .pipe(timeout(15_000), takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      const result = await firstValueFrom(
        this.http
          .put<unknown>(this.url(scope), review, {
            headers: {
              'X-CSRF-TOKEN': csrfHeader(token),
              'Idempotency-Key': attempt.key,
            },
          })
          .pipe(timeout(15_000), takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      confirmedModelGrant(result, review);
      this.cancel();
      this.notice.set(
        review.enabled
          ? 'Đã lưu quyền dùng model.'
          : 'Đã lưu yêu cầu thu hồi quyền.',
      );
      // A matched retry may return a historical receipt. Read the current state before another action.
      try {
        const state = await this.read(scope);
        if (this.current(scope)) this.setCurrent(scope, state);
      } catch (error) {
        if (this.current(scope) && !this.hideFor(error)) {
          this.needsRefresh.set(true);
          this.error.set('Đã xác nhận lưu. Hãy tải lại quyền để tiếp tục.');
        }
      }
    } catch (error) {
      if (!this.current(scope) || this.hideFor(error)) return;
      if (
        error instanceof HttpErrorResponse &&
        [400, 409].includes(error.status)
      ) {
        this.cancel();
        this.needsRefresh.set(true);
        this.error.set(
          'Quyền hoặc lựa chọn model đã thay đổi. Hãy tải lại để xem thông tin hiện tại.',
        );
      } else {
        this.error.set('Chưa xác định yêu cầu đã lưu hay chưa.');
      }
    } finally {
      if (this.current(scope)) this.busy.set(false);
    }
  }

  private setCurrent(scope: Scope, state: ModelGrant): void {
    this.state.set(state);
    const mismatched = scope.bindingRevision !== state.bindingRevision;
    this.needsRefresh.set(mismatched);
    if (mismatched)
      this.error.set(
        'Lựa chọn model đã thay đổi. Hãy tải lại để xem quyền hiện tại.',
      );
  }

  private url(scope: Scope): string {
    return `/api/v1/organizations/${encodeURIComponent(scope.organization)}/agents/${encodeURIComponent(scope.agent)}/model-grant`;
  }

  private read(scope: Scope): Promise<ModelGrant> {
    return firstValueFrom(
      this.http
        .get<unknown>(this.url(scope))
        .pipe(timeout(15_000), takeUntil(scope.closed)),
    ).then(modelGrant);
  }

  private current(scope: Scope): boolean {
    return (
      this.scope === scope &&
      this.organizationId() === scope.organization &&
      this.agentId() === scope.agent &&
      this.bindingRevision() === scope.bindingRevision &&
      this.auth.user()?.id === scope.account
    );
  }

  private hideFor(error: unknown): boolean {
    if (
      !(error instanceof HttpErrorResponse) ||
      ![401, 403, 404].includes(error.status)
    )
      return false;
    this.clear();
    return true;
  }

  private clear(): void {
    this.state.set(null);
    this.cancel();
    this.busy.set(false);
    this.error.set('');
    this.notice.set('');
    this.needsRefresh.set(false);
  }
}

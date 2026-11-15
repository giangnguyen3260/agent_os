import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import {RouterLink} from '@angular/router';
import {firstValueFrom, Subject, takeUntil, timeout} from 'rxjs';
import {AuthService} from '../../core/auth/auth.service';
import {csrfHeader} from '../provider-connections/provider-create-wire';
import {
  discoveryModelPage,
  ModelPage,
} from '../provider-connections/provider-rename-wire';
import {
  bindingConnections,
  bindingState,
  confirmedBinding,
  ModelBindingConnection,
  ModelBindingRequest,
  ModelBindingState,
  sameBinding,
} from './agent-model-binding-wire';
import {AgentModelGrantComponent} from './agent-model-grant.component';

interface Scope {
  organization: string;
  agent: string;
  account: string;
  closed: Subject<void>;
}

interface Review {
  request: ModelBindingRequest;
  label: string | null;
}

interface Attempt {
  request: ModelBindingRequest;
  key: string;
  sends: number;
}

@Component({
  selector: 'app-agent-model-binding',
  imports: [RouterLink, AgentModelGrantComponent],
  templateUrl: './agent-model-binding.component.html',
  styleUrl: './agent-model-binding.component.css',
})
export class AgentModelBindingComponent {
  readonly organizationId = input.required<string>();
  readonly agentId = input.required<string>();
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly grant = viewChild(AgentModelGrantComponent);
  private scope: Scope | null = null;
  readonly state = signal<ModelBindingState | null>(null);
  readonly connections = signal<ModelBindingConnection[] | null>(null);
  readonly connection = signal<ModelBindingConnection | null>(null);
  readonly modelPage = signal<ModelPage | null>(null);
  readonly model = signal<ModelPage['items'][number] | null>(null);
  readonly review = signal<Review | null>(null);
  readonly attempt = signal<Attempt | null>(null);
  readonly busy = signal(false);
  readonly message = signal('');
  readonly error = signal('');
  readonly requiresRefresh = signal(false);
  readonly statusLabels = {
    CURRENT: 'Lựa chọn hiện tại',
    STALE: 'Kết nối đã thay đổi — cần chọn lại',
    UNAVAILABLE: 'Kết nối hiện không khả dụng',
  } as const;

  constructor() {
    effect(onCleanup => {
      const organization = this.organizationId();
      const agent = this.agentId();
      const account = this.auth.user()?.id;
      if (!account) return;
      const scope = {organization, agent, account, closed: new Subject<void>()};
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
    return !!(
      this.model() ||
      this.review() ||
      this.attempt() ||
      this.grant()?.dirty()
    );
  }

  grantInProgress(): boolean {
    return !!(this.grant()?.dirty() || this.grant()?.busy());
  }

  async refresh(): Promise<void> {
    const scope = this.scope;
    if (!scope || !this.current(scope) || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    try {
      const latest = await this.readState(scope);
      if (!this.current(scope)) return;
      if (this.attempt())
        this.message.set('Đã tải lựa chọn hiện tại của Agent.');
      this.state.set(latest);
      this.requiresRefresh.set(false);
      this.resetSelection();
    } catch (error) {
      if (this.current(scope) && !this.hideFor(error))
        this.error.set('Không thể tải cấu hình model. Vui lòng thử lại.');
    } finally {
      if (this.current(scope)) this.busy.set(false);
    }
  }

  private base(scope: Scope): string {
    return `/api/v1/organizations/${encodeURIComponent(scope.organization)}/agents/${encodeURIComponent(scope.agent)}/model-binding`;
  }

  private readState(scope: Scope): Promise<ModelBindingState> {
    return firstValueFrom(
      this.http
        .get<unknown>(this.base(scope))
        .pipe(timeout(15_000), takeUntil(scope.closed)),
    ).then(bindingState);
  }

  private current(scope: Scope): boolean {
    return (
      this.scope === scope &&
      this.organizationId() === scope.organization &&
      this.agentId() === scope.agent &&
      this.auth.user()?.id === scope.account
    );
  }

  private hideFor(error: unknown, missing = true): boolean {
    if (
      error instanceof HttpErrorResponse &&
      (error.status === 401 ||
        error.status === 403 ||
        (missing && error.status === 404))
    ) {
      this.clear();
      return true;
    }
    return false;
  }

  private clear(): void {
    this.state.set(null);
    this.requiresRefresh.set(false);
    this.message.set('');
    this.error.set('');
    this.busy.set(false);
    this.resetSelection();
  }

  private resetSelection(): void {
    this.connections.set(null);
    this.connection.set(null);
    this.modelPage.set(null);
    this.model.set(null);
    this.review.set(null);
    this.attempt.set(null);
  }

  private canEdit(): boolean {
    return (
      !!this.state()?.editable &&
      !this.requiresRefresh() &&
      !this.busy() &&
      !this.attempt() &&
      !this.grantInProgress()
    );
  }

  async choose(): Promise<void> {
    const scope = this.scope;
    if (!scope || !this.current(scope) || !this.canEdit()) return;
    this.resetSelection();
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    try {
      const value = await firstValueFrom(
        this.http
          .get<unknown>(this.base(scope) + '/connections')
          .pipe(timeout(15_000), takeUntil(scope.closed)),
      );
      if (this.current(scope)) this.connections.set(bindingConnections(value));
    } catch (error) {
      if (this.current(scope) && !this.hideFor(error))
        this.error.set('Không thể tải các kết nối. Vui lòng thử lại.');
    } finally {
      if (this.current(scope)) this.busy.set(false);
    }
  }

  async selectConnection(id: string): Promise<void> {
    if (!this.canEdit() || this.review()) return;
    const connection =
      this.connections()?.find(item => item.connectionId === id) ?? null;
    this.connection.set(connection);
    this.modelPage.set(null);
    this.model.set(null);
    this.error.set('');
    if (connection && connection.modelCount > 0) await this.loadModels(0);
  }

  async loadModels(page: number): Promise<void> {
    const scope = this.scope;
    const connection = this.connection();
    if (
      !scope ||
      !this.current(scope) ||
      !connection ||
      !this.canEdit() ||
      this.review() ||
      page < 0 ||
      page * 20 >= connection.modelCount ||
      !Number.isInteger(page)
    )
      return;
    this.modelPage.set(null);
    this.model.set(null);
    this.error.set('');
    this.busy.set(true);
    try {
      const value = await firstValueFrom(
        this.http
          .get<unknown>(
            `/api/v1/organizations/${encodeURIComponent(scope.organization)}/provider-connections/${encodeURIComponent(connection.connectionId)}/checks/${encodeURIComponent(connection.checkId)}/models`,
            {params: {page}},
          )
          .pipe(timeout(15_000), takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      this.modelPage.set(
        discoveryModelPage(
          value,
          scope.organization,
          connection.checkId,
          {
            connectionId: connection.connectionId,
            expectedCredentialRevision: connection.credentialRevision,
          },
          {state: connection.catalogueState, count: connection.modelCount},
          page,
        ),
      );
    } catch (error) {
      if (this.current(scope) && !this.hideFor(error, false))
        this.error.set(
          'Không thể đọc danh sách model. Vui lòng tải lại các kết nối.',
        );
    } finally {
      if (this.current(scope)) this.busy.set(false);
    }
  }

  selectModel(id: string): void {
    if (!this.canEdit() || this.review()) return;
    this.model.set(
      this.modelPage()?.items.find(item => item.id === id) ?? null,
    );
  }

  reviewModel(clear = false): void {
    const state = this.state();
    const connection = this.connection();
    const model = this.model();
    if (!state || !this.canEdit() || this.review()) return;
    if (clear) {
      if (state.binding)
        this.review.set({
          request: {expectedRevision: state.revision, selection: null},
          label: null,
        });
      return;
    }
    if (!connection || !model) return;
    const selection = {
      connectionId: connection.connectionId,
      checkId: connection.checkId,
      modelId: model.id,
    };
    if (sameBinding(state.binding, selection)) {
      this.error.set('Agent đã gắn model này từ danh sách đang xem.');
      return;
    }
    this.error.set('');
    this.review.set({
      request: {expectedRevision: state.revision, selection},
      label: `${connection.connectionName} · ${model.id}`,
    });
  }

  cancel(): void {
    if (!this.canEdit()) return;
    this.resetSelection();
    this.error.set('');
  }

  async save(): Promise<void> {
    const scope = this.scope;
    const review = this.review();
    if (
      !scope ||
      !this.current(scope) ||
      !review ||
      this.busy() ||
      this.requiresRefresh()
    )
      return;
    const prior = this.attempt();
    if (prior && prior.sends >= 2) return;
    const attempt = prior
      ? {...prior, sends: prior.sends + 1}
      : {request: review.request, key: crypto.randomUUID(), sends: 1};
    this.attempt.set(attempt);
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    try {
      const token = await firstValueFrom(
        this.http
          .get<unknown>('/api/v1/auth/csrf')
          .pipe(timeout(15_000), takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      const response = await firstValueFrom(
        this.http
          .put<unknown>(this.base(scope), attempt.request, {
            headers: {
              'X-CSRF-TOKEN': csrfHeader(token),
              'Idempotency-Key': attempt.key,
            },
          })
          .pipe(timeout(15_000), takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      const saved = confirmedBinding(response, attempt.request);
      this.resetSelection();
      this.state.set(saved);
      this.message.set(
        saved.binding
          ? 'Đã lưu model cho Agent.'
          : 'Đã bỏ gắn model khỏi Agent.',
      );
      // A successful replay returns its historical snapshot. Re-read current state before another edit.
      try {
        const latest = await this.readState(scope);
        if (this.current(scope)) this.state.set(latest);
      } catch (error) {
        if (this.current(scope) && !this.hideFor(error)) {
          this.requiresRefresh.set(true);
          this.error.set(
            'Đã xác nhận lưu. Hãy tải lại trạng thái trước khi chỉnh sửa.',
          );
        }
      }
    } catch (error) {
      if (!this.current(scope) || this.hideFor(error)) return;
      if (
        error instanceof HttpErrorResponse &&
        (error.status === 400 || error.status === 409)
      ) {
        this.resetSelection();
        this.requiresRefresh.set(true);
        this.error.set(
          'Không thể lưu lựa chọn. Hãy tải lại trạng thái để xem cấu hình và danh sách kết nối hiện tại.',
        );
      } else {
        this.error.set('Chưa xác định yêu cầu đã lưu hay chưa.');
      }
    } finally {
      if (this.current(scope)) this.busy.set(false);
    }
  }
}

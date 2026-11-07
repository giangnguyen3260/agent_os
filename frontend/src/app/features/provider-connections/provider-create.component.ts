import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import {firstValueFrom, Subject, takeUntil} from 'rxjs';
import {AuthService, AuthUser} from '../../core/auth/auth.service';
import {uuid} from '../task-dependencies/task-dependencies-validation';
import {
  createErrorCode,
  csrfHeader,
  enabledProviders,
  normalizedName,
  providerMode,
  receiptFound,
  retryAfterSeconds,
  validName,
  validWorkspace,
  verifyCreated,
} from './provider-create-wire';
import {Provider} from './provider-list-wire';

interface Scope {
  actor: AuthUser;
  organization: string;
  closed: Subject<void>;
}

interface Pending {
  actor: string;
  organization: string;
  key: string;
}

const acceptJson = {Accept: 'application/json'};
const readTimeout = 15_000;
const createTimeout = 30_000;

@Component({
  selector: 'app-provider-create',
  standalone: true,
  templateUrl: './provider-create.component.html',
  styleUrl: './provider-create.component.css',
})
export class ProviderCreateComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly destroy = inject(DestroyRef);
  private readonly apiKey = viewChild<ElementRef<HTMLInputElement>>('apiKey');
  private scope: Scope | undefined;

  readonly organization = input.required<string>();
  readonly created = output<void>();
  readonly providers = signal<Provider[]>([]);
  readonly provider = signal<Provider | null>(null);
  readonly name = signal('');
  readonly workspace = signal('');
  readonly busy = signal(false);
  readonly pending = signal<Pending | null>(null);
  readonly error = signal('');
  readonly receiptMessage = signal('');
  readonly labels = {
    OPENAI: 'OpenAI',
    ANTHROPIC: 'Anthropic / Claude',
    GOOGLE_GEMINI: 'Google Gemini',
  };

  constructor() {
    effect(onCleanup => {
      const actor = this.auth.user();
      const organization = this.organization();
      untracked(() => this.reset());
      if (!actor || !uuid(actor.id) || !uuid(organization)) return;
      const scope = {actor, organization, closed: new Subject<void>()};
      this.scope = scope;
      onCleanup(() => {
        this.scope = undefined;
        scope.closed.next();
        scope.closed.complete();
        untracked(() => this.clearKey());
      });
      this.http
        .get<unknown>(this.base(scope) + '/options', {
          headers: acceptJson,
          observe: 'response',
          timeout: readTimeout,
        })
        .pipe(takeUntil(scope.closed))
        .subscribe({
          next: response => {
            if (
              !this.current(scope) ||
              response.status !== 200 ||
              response.headers.get('X-Provider-Create-Enabled') !== 'true'
            )
              return;
            try {
              const providers = enabledProviders(
                response.body,
                scope.organization,
              );
              this.providers.set(providers);
              this.provider.set(providers[0] ?? null);
            } catch {
              this.providers.set([]);
            }
          },
          error: () => {
            if (this.current(scope)) this.providers.set([]);
          },
        });
    });
  }

  selectProvider(value: string): void {
    const provider = this.providers().find(candidate => candidate === value);
    if (!provider) return;
    this.provider.set(provider);
    this.workspace.set('');
  }

  async submit(event: Event, keyInput: HTMLInputElement): Promise<void> {
    event.preventDefault();
    let apiKey = keyInput.value;
    keyInput.value = '';
    const scope = this.scope;
    const provider = this.provider();
    if (
      !scope ||
      !this.current(scope) ||
      !provider ||
      !this.providers().includes(provider) ||
      this.busy() ||
      this.pending()
    )
      return;
    this.error.set('');
    const name = normalizedName(this.name());
    const workspaceId =
      provider === 'ANTHROPIC' ? this.workspace().trim() || null : null;
    if (
      !validName(name) ||
      !/^[!-~]{1,4096}$/.test(apiKey) ||
      (workspaceId !== null && !validWorkspace(workspaceId))
    ) {
      this.error.set(
        'Kiểm tra tên kết nối, API key và Workspace ID rồi thử lại.',
      );
      return;
    }
    let locator: Pending | undefined;
    let sent = false;
    this.busy.set(true);
    try {
      locator = {
        actor: scope.actor.id,
        organization: scope.organization,
        key: globalThis.crypto.randomUUID(),
      };
      const token = await firstValueFrom(
        this.http
          .get<unknown>('/api/v1/auth/csrf', {
            headers: acceptJson,
            timeout: readTimeout,
          })
          .pipe(takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      const header = csrfHeader(token);
      const request = this.http
        .post<unknown>(
          this.base(scope),
          {
            provider,
            credentialMode: providerMode(provider),
            name,
            options: provider === 'ANTHROPIC' ? {workspaceId} : {},
            apiKey,
          },
          {
            headers: {
              ...acceptJson,
              'X-CSRF-TOKEN': header,
              'Idempotency-Key': locator.key,
            },
            observe: 'response',
            timeout: createTimeout,
          },
        )
        .pipe(takeUntil(scope.closed));
      apiKey = '';
      sent = true;
      const response = await firstValueFrom(request);
      if (!this.current(scope)) return;
      if (response.status !== 201 && response.status !== 200) {
        throw new Error('Unexpected Create response');
      }
      verifyCreated(response.body, scope.organization, scope.actor.id, {
        provider,
        name,
        workspaceId,
      });
      this.complete();
    } catch (failure) {
      if (!this.current(scope)) return;
      if (this.deny(failure)) return;
      if (!sent || !locator) {
        this.error.set(
          'Chưa gửi yêu cầu. Vui lòng nhập lại API key và thử lại.',
        );
      } else if (!this.knownRejection(failure)) {
        this.name.set('');
        this.workspace.set('');
        this.pending.set(locator);
      }
    } finally {
      apiKey = '';
      if (this.current(scope)) this.busy.set(false);
    }
  }

  async checkReceipt(): Promise<void> {
    const scope = this.scope;
    const locator = this.pending();
    if (
      !scope ||
      !locator ||
      !this.current(scope) ||
      locator.actor !== scope.actor.id ||
      locator.organization !== scope.organization ||
      this.busy()
    )
      return;
    this.busy.set(true);
    this.receiptMessage.set('');
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(this.base(scope) + '/receipts/' + locator.key, {
            headers: acceptJson,
            observe: 'response',
            timeout: readTimeout,
          })
          .pipe(takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      if (response.status !== 200)
        throw new Error('Unexpected receipt response');
      if (receiptFound(response.body, scope.organization, scope.actor.id)) {
        this.complete();
      } else {
        this.receiptMessage.set(
          'Chưa tìm thấy biên nhận. Kết quả vẫn chưa xác định.',
        );
      }
    } catch (failure) {
      if (this.current(scope) && !this.deny(failure)) {
        this.receiptMessage.set(
          'Không thể kiểm tra biên nhận. Vui lòng thử kiểm tra lại.',
        );
      }
    } finally {
      if (this.current(scope)) this.busy.set(false);
    }
  }

  private base(scope: Scope): string {
    return (
      '/api/v1/organizations/' + scope.organization + '/provider-connections'
    );
  }

  private current(scope: Scope): boolean {
    return (
      !this.destroy.destroyed &&
      this.scope === scope &&
      this.auth.user() === scope.actor &&
      this.organization() === scope.organization
    );
  }

  private reset(): void {
    this.clearKey();
    this.providers.set([]);
    this.provider.set(null);
    this.name.set('');
    this.workspace.set('');
    this.busy.set(false);
    this.pending.set(null);
    this.error.set('');
    this.receiptMessage.set('');
  }

  private clearKey(): void {
    const input = this.apiKey()?.nativeElement;
    if (input) input.value = '';
  }

  private deny(failure: unknown): boolean {
    if (
      !(failure instanceof HttpErrorResponse) ||
      (failure.status !== 401 && failure.status !== 403)
    )
      return false;
    this.reset();
    this.error.set(
      failure.status === 401
        ? 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'
        : 'Yêu cầu bị từ chối. Vui lòng tải lại trang để kiểm tra phiên và quyền truy cập.',
    );
    return true;
  }

  private knownRejection(failure: unknown): boolean {
    if (!(failure instanceof HttpErrorResponse)) return false;
    const code = createErrorCode(failure.error);
    if (failure.status === 400 && code === 'BAD_REQUEST') {
      this.error.set(
        'Yêu cầu không hợp lệ. Vui lòng kiểm tra thông tin rồi thử lại.',
      );
    } else if (failure.status === 429 && code === 'RATE_LIMITED') {
      const seconds = retryAfterSeconds(failure.headers.get('Retry-After'));
      this.error.set(
        seconds === null
          ? 'Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
          : `Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau ${seconds} giây.`,
      );
    } else if (
      failure.status === 409 &&
      (code === 'PROVIDER_DISABLED' || code === 'CONNECTION_LIMIT')
    ) {
      this.error.set(
        code === 'PROVIDER_DISABLED'
          ? 'Nhà cung cấp hiện đã bị tắt.'
          : 'Tổ chức đã đạt giới hạn số kết nối.',
      );
    } else {
      return false;
    }
    return true;
  }

  private complete(): void {
    this.name.set('');
    this.workspace.set('');
    this.pending.set(null);
    this.receiptMessage.set('');
    this.created.emit();
  }
}

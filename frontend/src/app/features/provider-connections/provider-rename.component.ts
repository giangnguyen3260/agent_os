import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import {firstValueFrom, Subject, takeUntil} from 'rxjs';
import {AuthService, AuthUser} from '../../core/auth/auth.service';
import {uuid} from '../task-dependencies/task-dependencies-validation';
import {
  createErrorCode,
  csrfHeader,
  enabledProviders,
  normalizedName,
  retryAfterSeconds,
  validName,
} from './provider-create-wire';
import {ProviderConnectionItem} from './provider-list-wire';
import {
  receiptFoundDisable,
  receiptFoundRename,
  ReviewedDisable,
  ReviewedRename,
  verifyDisabled,
  verifyRenamed,
} from './provider-rename-wire';

interface Scope {
  actor: AuthUser;
  organization: string;
  connection: ProviderConnectionItem;
  closed: Subject<void>;
}
type Review =
  | (ReviewedRename & {operation: 'rename'; beforeName: string})
  | (ReviewedDisable & {operation: 'disable'});
interface Pending {
  key: string;
  review: Review;
}
const headers = {Accept: 'application/json'};
const readTimeout = 15_000;

@Component({
  selector: 'app-provider-rename',
  standalone: true,
  templateUrl: './provider-rename.component.html',
  styleUrl: './provider-rename.component.css',
})
export class ProviderRenameComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly destroy = inject(DestroyRef);
  private scope: Scope | undefined;
  readonly organization = input.required<string>();
  readonly connection = input.required<ProviderConnectionItem>();
  readonly renamed = output<void>();
  readonly disabled = output<void>();
  readonly refreshRequested = output<void>();
  readonly enabled = signal(false);
  readonly disableEnabled = signal(false);
  readonly editing = signal(false);
  readonly name = signal('');
  readonly review = signal<Review | null>(null);
  readonly pending = signal<Pending | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly needsRefresh = signal(false);
  readonly receiptMessage = signal('');

  constructor() {
    effect(onCleanup => {
      const actor = this.auth.user(),
        organization = this.organization(),
        connection = this.connection();
      untracked(() => this.reset());
      if (
        !actor ||
        !uuid(actor.id) ||
        !uuid(organization) ||
        connection.organizationId !== organization ||
        !uuid(connection.id)
      )
        return;
      const scope = {
        actor,
        organization,
        connection,
        closed: new Subject<void>(),
      };
      this.scope = scope;
      onCleanup(() => {
        this.scope = undefined;
        scope.closed.next();
        scope.closed.complete();
      });
      this.http
        .get<unknown>(this.base(scope) + '/options', {
          headers,
          observe: 'response',
          timeout: readTimeout,
        })
        .pipe(takeUntil(scope.closed))
        .subscribe({
          next: response => {
            if (!this.current(scope) || response.status !== 200) return;
            try {
              // Validate options without applying enabled-provider gates to metadata Rename.
              enabledProviders(response.body, organization);
              this.enabled.set(
                response.headers.get('X-Provider-Rename-Enabled') === 'true',
              );
              this.disableEnabled.set(
                connection.state === 'ENABLED' &&
                  response.headers.get('X-Provider-Disable-Enabled') === 'true',
              );
            } catch {
              this.enabled.set(false);
            }
          },
          error: failure => {
            if (this.current(scope) && !this.deny(failure))
              this.enabled.set(false);
          },
        });
    });
  }

  start(): void {
    const scope = this.scope;
    if (
      !scope ||
      !this.current(scope) ||
      !this.enabled() ||
      this.review() ||
      this.busy() ||
      this.pending() ||
      this.needsRefresh()
    )
      return;
    this.name.set(scope.connection.name);
    this.error.set('');
    this.editing.set(true);
  }

  prepareReview(event: Event): void {
    event.preventDefault();
    const scope = this.scope;
    if (
      !scope ||
      !this.current(scope) ||
      !this.enabled() ||
      !this.editing() ||
      this.review() ||
      this.busy() ||
      this.pending() ||
      this.needsRefresh()
    )
      return;
    this.error.set('');
    const name = normalizedName(this.name());
    if (!validName(name)) {
      this.error.set('Tên kết nối phải có từ 1 đến 100 ký tự hợp lệ.');
      return;
    }
    if (name === scope.connection.name) {
      this.error.set('Tên kết nối không thay đổi. Vui lòng nhập tên mới.');
      return;
    }
    this.review.set({
      operation: 'rename',
      connectionId: scope.connection.id,
      expectedRevision: scope.connection.revision,
      beforeName: scope.connection.name,
      name,
    });
  }

  prepareDisable(): void {
    const scope = this.scope;
    if (
      !scope ||
      !this.current(scope) ||
      !this.disableEnabled() ||
      this.editing() ||
      this.review() ||
      this.busy() ||
      this.pending() ||
      this.needsRefresh()
    )
      return;
    this.error.set('');
    this.review.set({
      operation: 'disable',
      connectionId: scope.connection.id,
      expectedRevision: scope.connection.revision,
      name: scope.connection.name,
    });
  }

  edit(): void {
    if (this.busy() || this.pending()) return;
    this.review.set(null);
    this.error.set('');
  }

  async confirm(): Promise<void> {
    const scope = this.scope,
      review = this.review();
    if (
      !scope ||
      !review ||
      !this.current(scope) ||
      !(review.operation === 'rename'
        ? this.enabled()
        : this.disableEnabled()) ||
      this.busy() ||
      this.pending() ||
      this.needsRefresh()
    )
      return;
    this.busy.set(true);
    this.error.set('');
    let key: string | undefined;
    let sent = false;
    try {
      key = globalThis.crypto.randomUUID();
      const token = await firstValueFrom(
        this.http
          .get<unknown>('/api/v1/auth/csrf', {headers, timeout: readTimeout})
          .pipe(takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      const csrf = csrfHeader(token);
      sent = true;
      const response = await firstValueFrom(
        this.http
          .post<unknown>(
            this.base(scope) +
              '/' +
              review.connectionId +
              '/' +
              review.operation,
            review.operation === 'rename'
              ? {expectedRevision: review.expectedRevision, name: review.name}
              : {expectedRevision: review.expectedRevision},
            {
              headers: {
                ...headers,
                'X-CSRF-TOKEN': csrf,
                'Idempotency-Key': key,
              },
              observe: 'response',
              timeout: 30_000,
            },
          )
          .pipe(takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      if (response.status !== 201 && response.status !== 200)
        throw new Error('Invalid metadata response');
      if (review.operation === 'rename')
        verifyRenamed(
          response.body,
          scope.organization,
          scope.actor.id,
          review,
        );
      else
        verifyDisabled(
          response.body,
          scope.organization,
          scope.actor.id,
          review,
        );
      this.complete(review.operation);
    } catch (failure) {
      if (!this.current(scope) || this.deny(failure)) return;
      if (!sent || !key) {
        this.review.set(null);
        this.error.set(
          review.operation === 'rename'
            ? 'Chưa gửi yêu cầu đổi tên. Vui lòng xem lại và thử lại.'
            : 'Chưa gửi yêu cầu tắt kết nối. Vui lòng xem lại và thử lại.',
        );
      } else if (!this.knownRejection(failure, review.operation)) {
        this.pending.set({key, review});
        this.review.set(null);
        this.name.set('');
      }
    } finally {
      if (this.current(scope)) this.busy.set(false);
    }
  }

  async checkReceipt(): Promise<void> {
    const scope = this.scope,
      pending = this.pending();
    if (!scope || !pending || !this.current(scope) || this.busy()) return;
    this.busy.set(true);
    this.receiptMessage.set('');
    try {
      const response = await firstValueFrom(
        this.http
          .get<unknown>(this.base(scope) + '/receipts/' + pending.key, {
            headers,
            observe: 'response',
            timeout: readTimeout,
          })
          .pipe(takeUntil(scope.closed)),
      );
      if (!this.current(scope)) return;
      if (response.status !== 200)
        throw new Error('Invalid metadata receipt response');
      const review = pending.review;
      const found =
        review.operation === 'rename'
          ? receiptFoundRename(
              response.body,
              scope.organization,
              scope.actor.id,
              scope.connection.id,
              review,
            )
          : receiptFoundDisable(
              response.body,
              scope.organization,
              scope.actor.id,
              scope.connection.id,
              review,
            );
      if (found) this.complete(review.operation);
      else
        this.receiptMessage.set(
          'Chưa tìm thấy biên nhận. Kết quả vẫn chưa xác định.',
        );
    } catch (failure) {
      if (this.current(scope) && !this.deny(failure))
        this.receiptMessage.set(
          'Không thể kiểm tra biên nhận. Vui lòng thử kiểm tra lại.',
        );
    } finally {
      if (this.current(scope)) this.busy.set(false);
    }
  }

  private knownRejection(
    failure: unknown,
    operation: Review['operation'],
  ): boolean {
    if (!(failure instanceof HttpErrorResponse)) return false;
    const code = createErrorCode(failure.error);
    let message: string;
    if (failure.status === 400 && code === 'BAD_REQUEST')
      message =
        operation === 'rename'
          ? 'Yêu cầu đổi tên không hợp lệ. Vui lòng kiểm tra tên rồi xem lại.'
          : 'Yêu cầu tắt kết nối không hợp lệ. Vui lòng xem lại.';
    else if (failure.status === 404 && code === 'NOT_FOUND') {
      message = 'Không tìm thấy kết nối. Vui lòng tải lại kết nối.';
      this.needsRefresh.set(true);
    } else if (failure.status === 409 && code === 'REVISION_CONFLICT') {
      message =
        operation === 'rename'
          ? 'Kết nối đã thay đổi. Vui lòng tải lại kết nối và xem lại tên mới.'
          : 'Kết nối đã thay đổi. Vui lòng tải lại kết nối trước khi tắt.';
      this.needsRefresh.set(true);
    } else if (failure.status === 409 && code === 'NO_CHANGE') {
      message =
        operation === 'rename'
          ? 'Tên kết nối không thay đổi. Vui lòng tải lại kết nối.'
          : 'Kết nối đã tắt. Vui lòng tải lại kết nối.';
      this.needsRefresh.set(true);
    } else if (failure.status === 409 && code === 'REVISION_EXHAUSTED') {
      message =
        operation === 'rename'
          ? 'Không thể đổi tên ở phiên bản hiện tại. Vui lòng tải lại kết nối.'
          : 'Không thể tắt kết nối ở phiên bản hiện tại. Vui lòng tải lại kết nối.';
      this.needsRefresh.set(true);
    } else if (failure.status === 409 && code === 'IDEMPOTENCY_CONFLICT')
      message =
        operation === 'rename'
          ? 'Yêu cầu đổi tên bị xung đột. Vui lòng kiểm tra tên rồi xem lại.'
          : 'Yêu cầu tắt kết nối bị xung đột. Vui lòng xem lại.';
    else if (failure.status === 429 && code === 'RATE_LIMITED') {
      const seconds = retryAfterSeconds(failure.headers.get('Retry-After'));
      message =
        seconds === null
          ? 'Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
          : `Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau ${seconds} giây.`;
    } else return false;
    this.review.set(null);
    this.error.set(message);
    return true;
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

  private current(scope: Scope): boolean {
    return (
      !this.destroy.destroyed &&
      this.scope === scope &&
      this.auth.user() === scope.actor &&
      this.organization() === scope.organization &&
      this.connection() === scope.connection
    );
  }
  private base(scope: Scope): string {
    return (
      '/api/v1/organizations/' + scope.organization + '/provider-connections'
    );
  }
  private reset(): void {
    this.enabled.set(false);
    this.disableEnabled.set(false);
    this.editing.set(false);
    this.name.set('');
    this.review.set(null);
    this.pending.set(null);
    this.busy.set(false);
    this.error.set('');
    this.needsRefresh.set(false);
    this.receiptMessage.set('');
  }
  private complete(operation: Review['operation']): void {
    this.review.set(null);
    this.pending.set(null);
    this.name.set('');
    this.receiptMessage.set('');
    if (operation === 'rename') this.renamed.emit();
    else this.disabled.emit();
  }
}

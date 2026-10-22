import {HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  effect,
  inject,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {DatePipe} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {firstValueFrom, Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
import {ApprovalNavigationState} from './approval-navigation.guard';
import {
  ApprovalDetailService,
  ApprovalDetail,
  DecisionBody,
  Operation,
  validDetail,
  validDecision,
} from './approval-detail.service';

@Component({
  selector: 'app-approval-detail',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './approval-detail.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './approval-detail.component.css',
  ],
})
export class ApprovalDetailComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApprovalDetailService);
  private readonly navigation = inject(ApprovalNavigationState);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private allowedNavigationId = 0;
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  id = '';
  approvalId = '';
  readonly stage = signal<'detail' | 'verify' | 'reject'>('detail');
  readonly acknowledged = signal(false);
  readonly busy = signal(false);
  readonly outcome = signal('');
  readonly uncertain = signal(false);
  reason = '';
  private unresolvedIdentity: {
    org: string;
    id: string;
    accountId: string;
    key: string;
  } | null = null;
  private retryBlocked = false;
  private frozen: {
    org: string;
    id: string;
    accountId: string;
    ambiguous: boolean;
    key: string;
    body: DecisionBody;
    operation: Operation;
  } | null = null;
  readonly context = signal<Workspace | null>(null);
  readonly result = signal<ApprovalDetail | null>(null);
  readonly loading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly menuOpen = signal(false);
  constructor() {
    const check = (org: string, id: string) =>
      (org === this.id && id === this.approvalId) || this.canLeave();
    this.navigation.check = check;
    this.destroy.onDestroy(() => {
      if (this.navigation.check === check) this.navigation.check = null;
    });
    effect(() => {
      const account = this.auth.user()?.id;
      if (account !== this.account) {
        this.account = account;
        this.frozen = null;
        this.unresolvedIdentity = null;
        this.retryBlocked = false;
        this.uncertain.set(false);
        this.busy.set(false);
        this.outcome.set('');
        this.clear();
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => this.clear());
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      // canActivate checks unsaved state even when Angular reuses this component.
      this.clear();
      this.busy.set(false);
      this.frozen = null;
      this.unresolvedIdentity = null;
      this.retryBlocked = false;
      this.uncertain.set(false);
      this.outcome.set('');
      this.id = params.get('id') || '';
      this.approvalId = params.get('approvalId') || '';
      void this.load();
    });
  }

  private clear(): void {
    this.acknowledged.set(false);
    this.stage.set('detail');
    this.reason = '';
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.result.set(null);
    this.context.set(null);
    this.loading.set(false);
    this.error.set('');
    this.closeMenu();
  }
  async load(): Promise<void> {
    if (this.busy()) return;
    this.clear();
    this.denied.set(false);
    if (!uuid(this.id) || !uuid(this.approvalId)) {
      this.error.set('Invalid approval address.');
      return;
    }
    const epoch = this.epoch;
    const account = this.auth.user()?.id;
    const active = () =>
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      account === this.auth.user()?.id;
    this.loading.set(true);
    try {
      const context = (await firstValueFrom(this.api.workspace(this.id)))?.data;
      if (!active()) return;
      if (
        !account ||
        !context ||
        Object.keys(context).sort().join(',') !== 'id,name,role' ||
        typeof context.name !== 'string' ||
        !context.name.trim() ||
        context.name.length > 255 ||
        context.id !== this.id ||
        !['ADMIN', 'MEMBER'].includes(context.role)
      ) {
        this.eraseDeniedSubmission();
        this.clear();
        this.denied.set(true);
        return;
      }
      this.context.set(context);
      this.requests.add(
        this.api.get(this.id, this.approvalId).subscribe({
          next: response => {
            if (!active()) return;
            this.loading.set(false);
            if (!validDetail(response?.data, this.id, this.approvalId)) {
              this.error.set(
                'Invalid approval response. Try again to load current data.',
              );
              return;
            }
            this.result.set(response.data);
            if (this.uncertain() && response.data.decision) {
              this.frozen = null;
              this.unresolvedIdentity = null;
              this.retryBlocked = false;
              this.uncertain.set(false);
              this.outcome.set(
                'A decision record is available. Review the recorded result; execution has not started.',
              );
            }
            const saved = response.data.decision;
            if (
              this.frozen &&
              saved &&
              saved.actorId === account &&
              saved.status ===
                (this.frozen.operation === 'approve'
                  ? 'APPROVED'
                  : 'REJECTED') &&
              saved.snapshotVersion === this.frozen.body.snapshotVersion &&
              saved.snapshotSha256 === this.frozen.body.snapshotSha256 &&
              (saved.reason ?? '') === this.frozen.body.reason
            ) {
              this.frozen = null;
              this.unresolvedIdentity = null;
              this.retryBlocked = false;
              this.uncertain.set(false);
              this.outcome.set('Decision recorded. Execution has not started.');
            }
          },
          error: error => {
            if (active()) this.failure(error);
          },
        }),
      );
    } catch (error) {
      if (active()) this.failure(error);
    }
  }
  private failure(error: unknown): void {
    this.result.set(null);
    this.context.set(null);
    this.loading.set(false);
    if (
      (error instanceof HttpErrorResponse &&
        [401, 403, 404].includes(error.status)) ||
      (error instanceof AuthFailure &&
        ['unauthorized', 'not-found'].includes(error.kind))
    ) {
      this.eraseDeniedSubmission();
      this.busy.set(false);
      this.denied.set(true);
    } else this.error.set('Unable to load approval. Try again.');
  }
  review(): void {
    if (!this.result()?.eligibility.canApprove || this.busy() || this.frozen)
      return;
    this.stage.set('verify');
  }
  confirmScope(): void {
    if (!this.result()?.eligibility.canApprove || this.frozen) return;
    this.acknowledged.set(true);
    this.stage.set('detail');
  }
  reject(): void {
    if (!this.result()?.eligibility.canReject || this.busy() || this.frozen)
      return;
    this.reason = '';
    this.stage.set('reject');
  }
  cancel(): void {
    this.reason = '';
    this.stage.set('detail');
  }
  submit(operation: Operation): void {
    const data = this.result();
    if (
      !data?.snapshot ||
      this.busy() ||
      this.frozen ||
      this.uncertain() ||
      this.unresolvedIdentity ||
      (operation === 'approve'
        ? !data.eligibility.canApprove || !this.acknowledged()
        : !data.eligibility.canReject ||
          !this.reason.trim() ||
          this.reason.trim().length > 2000)
    )
      return;
    const accountId = this.auth.user()?.id;
    if (!accountId) return;
    this.retryBlocked = false;
    this.frozen = {
      org: this.id,
      id: this.approvalId,
      accountId,
      ambiguous: false,
      key: crypto.randomUUID(),
      operation,
      body: Object.freeze({
        snapshotVersion: data.snapshot.version,
        snapshotSha256: data.snapshot.payloadSha256,
        acknowledgement: operation === 'approve',
        reason: operation === 'reject' ? this.reason.trim() : '',
      }),
    };
    this.send();
  }
  retry(): void {
    if (this.canRetry() && !this.busy() && !this.loading()) this.send();
  }
  private send(): void {
    const request = this.frozen;
    if (
      !request ||
      this.destroy.destroyed ||
      request.org !== this.id ||
      request.id !== this.approvalId ||
      request.accountId !== this.auth.user()?.id ||
      this.retryBlocked ||
      this.denied()
    )
      return;
    const epoch = this.epoch,
      account = this.auth.user()?.id;
    const active = () =>
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      account === this.auth.user()?.id;
    this.busy.set(true);
    this.error.set('');
    this.outcome.set('');
    this.requests.add(
      this.api
        .decide(
          request.org,
          request.id,
          request.operation,
          request.key,
          request.body,
        )
        .subscribe({
          next: response => {
            if (!active()) return;
            this.busy.set(false);
            const d = response?.data;
            if (
              !validDecision(d, request.id) ||
              d.actorId !== account ||
              d.status !==
                (request.operation === 'approve' ? 'APPROVED' : 'REJECTED') ||
              d.snapshotVersion !== request.body.snapshotVersion ||
              d.snapshotSha256 !== request.body.snapshotSha256 ||
              (d.reason ?? '') !== request.body.reason
            ) {
              request.ambiguous = true;
              this.uncertain.set(true);
              this.outcome.set(
                'Response could not be verified. Check the recorded result or retry the same decision.',
              );
              return;
            }
            this.frozen = null;
            this.unresolvedIdentity = null;
            this.retryBlocked = false;
            this.uncertain.set(false);
            this.acknowledged.set(false);
            this.outcome.set('Decision recorded. Execution has not started.');
            void this.load();
          },
          error: error => {
            if (!active()) return;
            this.busy.set(false);
            if (
              error instanceof HttpErrorResponse &&
              [400, 401, 403, 404, 409].includes(error.status)
            ) {
              this.acknowledged.set(false);
              this.reason = '';
              this.stage.set('detail');
              this.result.set(null);
              this.context.set(null);
              if ([401, 403, 404].includes(error.status)) {
                this.eraseDeniedSubmission();
                this.denied.set(true);
              } else if (request.ambiguous) {
                this.retryBlocked = true;
                this.uncertain.set(true);
                this.outcome.set(
                  'The original outcome remains unknown. This retry was refused; check the recorded result before taking another action.',
                );
              } else {
                this.frozen = null;
                this.retryBlocked = false;
                this.uncertain.set(false);
                this.error.set(
                  'Decision was not accepted. Refresh the request before trying again.',
                );
              }
            } else {
              request.ambiguous = true;
              this.uncertain.set(true);
              this.outcome.set(
                'Decision outcome is unknown. Check the recorded result or retry the same decision.',
              );
            }
          },
        }),
    );
  }
  canRetry(): boolean {
    return (
      !!this.frozen &&
      !this.retryBlocked &&
      !this.denied() &&
      !!this.context() &&
      this.uncertain() &&
      this.frozen.org === this.id &&
      this.frozen.id === this.approvalId &&
      this.frozen.accountId === this.auth.user()?.id
    );
  }
  private eraseDeniedSubmission(): void {
    const pending = this.frozen;
    const unknown =
      this.uncertain() || !!pending?.ambiguous || !!this.unresolvedIdentity;
    if (pending && unknown)
      this.unresolvedIdentity = {
        org: pending.org,
        id: pending.id,
        accountId: pending.accountId,
        key: pending.key,
      };
    this.frozen = null;
    this.retryBlocked = true;
    this.reason = '';
    this.stage.set('detail');
    this.acknowledged.set(false);
    this.uncertain.set(unknown);
    this.outcome.set(
      unknown
        ? 'The original decision outcome remains unknown. Access is unavailable; private submission details were cleared. Check the recorded result when access is restored.'
        : '',
    );
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (
      this.busy() ||
      this.frozen ||
      this.unresolvedIdentity ||
      this.reason.trim() ||
      this.acknowledged()
    )
      event.preventDefault();
  }
  canLeave(): boolean {
    const dirty =
      this.busy() ||
      !!this.frozen ||
      !!this.unresolvedIdentity ||
      !!this.reason.trim() ||
      this.acknowledged();
    if (!dirty) return true;
    const navigationId = this.router.getCurrentNavigation()?.id;
    if (navigationId && navigationId === this.allowedNavigationId) return true;
    const leave = window.confirm(
      'Leave this approval? An unconfirmed decision may already have been recorded.',
    );
    if (leave && navigationId) this.allowedNavigationId = navigationId;
    return leave;
  }
  blockerLabel(code: string): string {
    const labels: Record<string, string> = {
      NO_GRANT: 'No active approver grant matches this Team, action and risk.',
      NO_SCOPED_GRANT:
        'No active approver grant matches this Team, action and risk.',
      LEGACY_UNSEALED: 'A sealed action snapshot is unavailable.',
      INVALID_SNAPSHOT:
        'The snapshot integrity or current action binding could not be verified.',
      MISSING_REQUESTER: 'The requesting human identity is unavailable.',
      INACTIVE_ACTION: 'The current action is unavailable.',
      REQUESTER_MOVED: 'The requester Agent is no longer in this Team.',
      HEAD_CHANGED: 'The current action no longer matches this snapshot.',
      CURRENT_HEAD_CHANGED:
        'The current action no longer matches this snapshot.',
      SELF_DECISION: 'You cannot decide a request you initiated.',
      SELF_APPROVAL: 'You cannot approve your own request.',
      EXPIRED: 'The action snapshot has expired.',
      INVALIDATED: 'The current action no longer matches this snapshot.',
      SNAPSHOT_UNAVAILABLE: 'A sealed action snapshot is unavailable.',
      SNAPSHOT_MISSING: 'A sealed action snapshot is unavailable.',
      NOT_PENDING: 'This request is not pending a new decision.',
      INACTIVE_PARENT: 'The parent Team or action is not active.',
    };
    return (
      labels[code] ||
      'This request cannot currently be decided. Refresh and review its authorization and snapshot.'
    );
  }
  toggleMenu(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#detail-menu')
      ?.showModal();
  }
  closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#detail-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="detail-menu"]')
      ?.focus();
  }
}

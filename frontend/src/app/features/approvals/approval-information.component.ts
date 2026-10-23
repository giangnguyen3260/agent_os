import {HttpErrorResponse} from '@angular/common/http';
import {DatePipe} from '@angular/common';
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
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Subscription, firstValueFrom, forkJoin, of} from 'rxjs';
import {AuthService, Workspace} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
import {ApprovalDetailService, validDetail} from './approval-detail.service';
import {ApprovalNavigationState} from './approval-navigation.guard';
import {
  ApprovalInformationService,
  Information,
  InformationBody,
  InformationOperation,
  informationText,
  validInformation,
  validInformationResult,
} from './approval-information.service';
interface PendingInformation {
  org: string;
  id: string;
  account: string;
  operation: InformationOperation;
  key: string;
  body: InformationBody;
  ambiguous: boolean;
}
@Component({
  selector: 'app-approval-information',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe],
  templateUrl: './approval-information.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './approval-detail.component.css',
    './approval-information.component.css',
  ],
})
export class ApprovalInformationComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApprovalInformationService);
  private readonly detail = inject(ApprovalDetailService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly navigation = inject(ApprovalNavigationState);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  private allowedNavigationId = 0;
  private pending: PendingInformation | null = null;
  private unresolved: {
    org: string;
    id: string;
    account: string;
    key: string;
    revision: number;
  } | null = null;
  private retryBlocked = false;
  id = '';
  approvalId = '';
  readonly normalized = informationText;
  question = '';
  answer = '';
  evidence = '';
  readonly data = signal<Information | null>(null);
  readonly context = signal<Workspace | null>(null);
  readonly title = signal('Approval information');
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly unknown = signal(false);
  readonly notice = signal('');
  readonly menuOpen = signal(false);
  constructor() {
    const check = (org: string, id: string) =>
      (org === this.id && id === this.approvalId) || this.canLeave();
    this.navigation.check = check;
    this.destroy.onDestroy(() => {
      this.clear();
      if (this.navigation.check === check) this.navigation.check = null;
    });
    effect(() => {
      const account = this.auth.user()?.id;
      if (account !== this.account) {
        this.account = account;
        this.reset();
        this.denied.set(true);
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.reset();
      this.id = params.get('id') || '';
      this.approvalId = params.get('approvalId') || '';
      void this.load();
    });
  }
  private inputsClear() {
    this.question = '';
    this.answer = '';
    this.evidence = '';
  }
  private clear() {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.data.set(null);
    this.context.set(null);
    this.title.set('Approval information');
    this.inputsClear();
    this.loading.set(false);
    this.error.set('');
    this.closeMenu();
  }
  private reset() {
    this.clear();
    this.pending = null;
    this.unresolved = null;
    this.retryBlocked = false;
    this.unknown.set(false);
    this.notice.set('');
    this.busy.set(false);
    this.denied.set(false);
  }
  async load() {
    if (this.busy()) return;
    this.clear();
    this.denied.set(false);
    if (!uuid(this.id) || !uuid(this.approvalId)) {
      this.error.set('Invalid approval address.');
      return;
    }
    const epoch = this.epoch,
      account = this.auth.user()?.id;
    const active = () =>
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      account === this.auth.user()?.id;
    this.loading.set(true);
    try {
      const context = (await firstValueFrom(this.detail.workspace(this.id)))
        ?.data;
      if (!active()) return;
      if (
        !account ||
        !context ||
        Object.keys(context).sort().join(',') !== 'id,name,role' ||
        context.id !== this.id ||
        typeof context.name !== 'string' ||
        !context.name.trim() ||
        context.name.length > 255 ||
        !['ADMIN', 'MEMBER'].includes(context.role)
      ) {
        this.deny();
        return;
      }
      this.context.set(context);
      const correlation = this.pending
        ? {key: this.pending.key, revision: this.pending.body.revision}
        : this.unresolved;
      this.requests.add(
        forkJoin({
          receipt: correlation
            ? this.api.receipt(this.id, this.approvalId, correlation.key)
            : of({data: null}),
          detail: this.detail.get(this.id, this.approvalId),
          information: this.api.get(this.id, this.approvalId),
        }).subscribe({
          next: response => {
            if (!active()) return;
            this.loading.set(false);
            const d = response.detail?.data,
              i = response.information?.data;
            if (
              !validDetail(d, this.id, this.approvalId) ||
              !validInformation(i, this.approvalId) ||
              d.informationRevision !== i.revision ||
              (i.canRequest &&
                (!d.eligibility.canApprove ||
                  d.request.status !== 'PENDING')) ||
              (i.canReply && d.request.status !== 'NEEDS_INFORMATION')
            ) {
              this.error.set(
                'Unable to verify the current conversation. Refresh before taking action.',
              );
              return;
            }
            this.title.set(d.request.title);
            this.data.set(i);
            const recovered = response.receipt?.data;
            if (this.unknown() && correlation && recovered !== null) {
              if (
                !validInformationResult(recovered, this.approvalId) ||
                recovered.revision !== correlation.revision + 1 ||
                i.revision < recovered.revision ||
                i.entries[recovered.revision - 1]?.id !== recovered.entryId ||
                i.entries[recovered.revision - 1]?.actorId !== account
              ) {
                this.data.set(null);
                this.error.set(
                  'Unable to verify the original submission receipt. Refresh before taking action.',
                );
                return;
              }
              this.pending = null;
              this.unresolved = null;
              this.retryBlocked = false;
              this.unknown.set(false);
              this.notice.set(
                'The original submission is recorded. Review the conversation before taking another action.',
              );
            }
          },
          error: e => {
            if (active()) this.failure(e);
          },
        }),
      );
    } catch (e) {
      if (active()) this.failure(e);
    }
  }
  private failure(e: unknown) {
    this.loading.set(false);
    this.data.set(null);
    this.context.set(null);
    this.inputsClear();
    if (e instanceof HttpErrorResponse && [401, 403, 404].includes(e.status))
      this.deny();
    else this.error.set('Unable to load conversation. Try again.');
  }
  private deny() {
    const p = this.pending;
    const unknown = this.unknown() || !!p?.ambiguous || !!this.unresolved;
    if (p && unknown)
      this.unresolved = {
        org: p.org,
        id: p.id,
        account: p.account,
        key: p.key,
        revision: p.body.revision,
      };
    this.pending = null;
    this.retryBlocked = true;
    this.clear();
    this.busy.set(false);
    this.denied.set(true);
    this.unknown.set(unknown);
    this.notice.set(
      unknown
        ? 'The original outcome remains unknown. Access is unavailable; private input was cleared. Check the conversation when access is restored.'
        : '',
    );
  }
  send(operation: InformationOperation) {
    const d = this.data(),
      account = this.auth.user()?.id;
    if (
      !d ||
      !account ||
      this.busy() ||
      this.loading() ||
      this.pending ||
      this.unresolved ||
      this.unknown()
    )
      return;
    let body: InformationBody;
    if (operation === 'request-information') {
      if (
        !d.canRequest ||
        !informationText(this.question) ||
        this.question.length > 2000
      )
        return;
      body = {revision: d.revision, question: informationText(this.question)};
    } else {
      if (
        !d.canReply ||
        !d.latestQuestionId ||
        !informationText(this.answer) ||
        this.answer.length > 2000 ||
        this.evidence.length > 1000
      )
        return;
      body = {
        revision: d.revision,
        questionId: d.latestQuestionId,
        answer: informationText(this.answer),
        evidence: informationText(this.evidence) || null,
      };
    }
    this.pending = {
      org: this.id,
      id: this.approvalId,
      account,
      operation,
      key: crypto.randomUUID(),
      body: Object.freeze(body),
      ambiguous: false,
    };
    this.retryBlocked = false;
    this.transmit();
  }
  canRetry() {
    return (
      !!this.pending &&
      !this.retryBlocked &&
      !this.denied() &&
      !!this.context() &&
      this.unknown() &&
      this.pending.org === this.id &&
      this.pending.id === this.approvalId &&
      this.pending.account === this.auth.user()?.id
    );
  }
  retry() {
    if (this.canRetry() && !this.busy() && !this.loading()) this.transmit();
  }
  private transmit() {
    const p = this.pending;
    if (
      !p ||
      this.busy() ||
      this.retryBlocked ||
      this.denied() ||
      p.org !== this.id ||
      p.id !== this.approvalId ||
      p.account !== this.auth.user()?.id
    )
      return;
    const epoch = this.epoch;
    const active = () =>
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      p.account === this.auth.user()?.id;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.requests.add(
      this.api.send(p.org, p.id, p.operation, p.key, p.body).subscribe({
        next: response => {
          if (!active()) return;
          this.busy.set(false);
          const r = response?.data;
          if (
            !validInformationResult(r, p.id) ||
            r.revision !== p.body.revision + 1 ||
            r.status !==
              (p.operation === 'reply' ? 'PENDING' : 'NEEDS_INFORMATION')
          ) {
            p.ambiguous = true;
            this.unknown.set(true);
            this.notice.set(
              'Response could not be verified. Check the conversation or retry the same submission.',
            );
            return;
          }
          this.pending = null;
          this.unresolved = null;
          this.retryBlocked = false;
          this.unknown.set(false);
          this.inputsClear();
          this.notice.set(
            'Information recorded. A new review is required; execution has not started.',
          );
          void this.load();
        },
        error: e => {
          if (!active()) return;
          this.busy.set(false);
          if (
            e instanceof HttpErrorResponse &&
            [400, 401, 403, 404, 409].includes(e.status)
          ) {
            this.inputsClear();
            this.data.set(null);
            this.context.set(null);
            if ([401, 403, 404].includes(e.status)) this.deny();
            else if (p.ambiguous) {
              this.retryBlocked = true;
              this.unknown.set(true);
              this.notice.set(
                'The original outcome remains unknown. This retry was refused. Check the conversation.',
              );
            } else {
              this.pending = null;
              this.error.set(
                'Submission was not accepted. Refresh the conversation before trying again.',
              );
            }
          } else {
            p.ambiguous = true;
            this.unknown.set(true);
            this.notice.set(
              'Submission outcome is unknown. Check the conversation or retry the same submission.',
            );
          }
        },
      }),
    );
  }
  cancel() {
    if (this.busy() || this.unknown()) return;
    this.inputsClear();
    void this.router.navigate([
      '/workspace',
      this.id,
      'approvals',
      this.approvalId,
    ]);
  }
  private dirty() {
    return (
      this.busy() ||
      !!this.pending ||
      !!this.unresolved ||
      !!informationText(this.question) ||
      !!informationText(this.answer) ||
      !!informationText(this.evidence)
    );
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(
    e: BeforeUnloadEvent,
  ) {
    if (this.dirty()) e.preventDefault();
  }
  canLeave() {
    if (!this.dirty()) return true;
    const id = this.router.getCurrentNavigation()?.id;
    if (id && id === this.allowedNavigationId) return true;
    const leave = window.confirm(
      'Leave this conversation? Unsaved input will be lost; an unconfirmed submission may already be recorded.',
    );
    if (leave && id) this.allowedNavigationId = id;
    return leave;
  }
  toggleMenu() {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#information-menu')
      ?.showModal();
  }
  closeMenu() {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#information-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="information-menu"]')
      ?.focus();
  }
}

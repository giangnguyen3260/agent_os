import {DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
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
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Subscription, firstValueFrom, from, timeout} from 'rxjs';
import {AuthService, Workspace} from '../../core/auth/auth.service';
import {
  DirectoryItem,
  DirectoryPage,
  TeamDirectoryService,
  validDirectory,
} from '../teams/team-directory.service';
import {uuid} from '../teams/team.service';
import {GrantNavigationState} from './grant-navigation.guard';
import {
  Command,
  GrantManagementService,
  History,
  IssueContext,
  Operation,
  Result,
  RevokeContext,
  exact,
  reasonText,
  risks,
  stamp,
  validHistory,
  validIssue,
  validReason,
  validResult,
  validRevoke,
} from './grant-management.service';

interface Correlation {
  org: string;
  account: string;
  actor: string;
  grant: string;
  operation: Operation;
  key: string;
  revision: number;
}
interface Pending extends Correlation {
  body: Command;
  ambiguous: boolean;
}
@Component({
  selector: 'app-grant-management',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './grant-management.component.html',
  styleUrls: [
    './member-access.component.css',
    './grant-management.component.css',
  ],
})
export class GrantManagementComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(GrantManagementService);
  private readonly directory = inject(TeamDirectoryService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly navigation = inject(GrantNavigationState);
  private readonly destroy = inject(DestroyRef);
  private requests = new Subscription();
  private epoch = 0;
  private teamEpoch = 0;
  private historyEpoch = 0;
  private actor = this.auth.user()?.id;
  readonly identityChecking = signal(false);
  private allowedNavigation = 0;
  private pending: Pending | null = null;
  private unresolved: Correlation | null = null;
  id = '';
  accountId = '';
  grantId = '';
  teamId = '';
  risk = '';
  duration = '';
  reason = '';
  acknowledgement = false;
  teamQuery = '';
  teamPage = 0;
  historyPage = 0;
  readonly risks = risks;
  readonly workspace = signal<Workspace | null>(null);
  readonly issue = signal<IssueContext | null>(null);
  readonly revoke = signal<RevokeContext | null>(null);
  readonly history = signal<History | null>(null);
  readonly teams = signal<DirectoryPage | null>(null);
  readonly result = signal<Result | null>(null);
  readonly stage = signal<'edit' | 'review'>('edit');
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly teamLoading = signal(false);
  readonly historyLoading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly unknown = signal(false);
  constructor() {
    const check = (org: string, account: string, grant: string) =>
      (org === this.id &&
        account === this.accountId &&
        grant === this.grantId) ||
      this.canLeave();
    this.navigation.check = check;
    this.destroy.onDestroy(() => {
      this.reset();
      if (this.navigation.check === check) this.navigation.check = null;
    });
    effect(() => {
      const actor = this.auth.user()?.id;
      if (actor !== this.actor) {
        this.actor = actor;
        this.reset();
        this.denied.set(true);
      }
    });
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.reset();
      this.id = params.get('id') || '';
      this.accountId = params.get('accountId') || '';
      this.grantId = params.get('grantId') || '';
      void this.load();
    });
  }
  get operation(): Operation {
    return this.grantId ? 'REVOKE' : 'ISSUE';
  }
  recipient() {
    return this.issue()?.recipient || this.revoke()?.recipient;
  }
  private active(epoch: number, actor: string | undefined) {
    return (
      !this.identityChecking() &&
      !this.destroy.destroyed &&
      epoch === this.epoch &&
      actor === this.auth.user()?.id
    );
  }
  private clearPrivate() {
    this.epoch++;
    this.teamEpoch++;
    this.historyEpoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.workspace.set(null);
    this.issue.set(null);
    this.revoke.set(null);
    this.teams.set(null);
    this.history.set(null);
    this.result.set(null);
    this.loading.set(false);
    this.busy.set(false);
    this.teamLoading.set(false);
    this.historyLoading.set(false);
    this.error.set('');
    this.teamId = '';
    this.risk = '';
    this.duration = '';
    this.reason = '';
    this.acknowledgement = false;
    this.teamQuery = '';
    this.teamPage = 0;
    this.historyPage = 0;
    this.stage.set('edit');
  }
  private reset() {
    this.clearPrivate();
    this.pending = null;
    this.unresolved = null;
    this.unknown.set(false);
    this.denied.set(false);
    this.notice.set('');
  }
  private correlation(): Correlation | null {
    return this.pending || this.unresolved;
  }
  private opaque(p: Correlation): Correlation {
    return {
      org: p.org,
      account: p.account,
      actor: p.actor,
      grant: p.grant,
      operation: p.operation,
      key: p.key,
      revision: p.revision,
    };
  }
  private deny() {
    const p = this.correlation();
    const unknown = this.unknown() || !!this.pending || !!this.unresolved;
    this.unresolved = unknown && p ? this.opaque(p) : null;
    this.pending = null;
    this.clearPrivate();
    this.denied.set(true);
    this.unknown.set(unknown);
    this.notice.set(
      unknown
        ? 'Kết quả cũ vẫn chưa xác định. Nội dung riêng tư đã xóa; khi có lại quyền, chỉ kiểm tra biên nhận của thao tác cũ.'
        : '',
    );
  }
  private failure(e: unknown) {
    if (e instanceof HttpErrorResponse && [401, 403, 404].includes(e.status)) {
      this.deny();
      return;
    }
    const correlation = this.correlation();
    if (correlation) {
      this.unresolved = this.opaque(correlation);
      this.pending = null;
      this.clearPrivate();
      this.unknown.set(true);
      this.notice.set(
        'Không thể xác thực dữ liệu. Kết quả thao tác cũ chưa xác định; chỉ kiểm tra biên nhận chính xác.',
      );
      return;
    }
    this.clearPrivate();
    this.error.set('Không thể xác thực dữ liệu grant. Vui lòng tải lại.');
  }
  private async authorize(
    epoch: number,
    actor: string | undefined,
  ): Promise<Workspace | null> {
    const r = (await firstValueFrom(this.api.workspace(this.id)))?.data;
    if (!this.active(epoch, actor)) return null;
    if (
      !actor ||
      !exact(r, 'id name role') ||
      r?.id !== this.id ||
      r.role !== 'ADMIN' ||
      typeof r.name !== 'string' ||
      !r.name.trim() ||
      r.name.length > 255
    ) {
      this.deny();
      return null;
    }
    return r;
  }
  async load() {
    if (this.busy() || this.identityChecking()) return;
    this.clearPrivate();
    this.denied.set(false);
    if (
      !uuid(this.id) ||
      !uuid(this.accountId) ||
      (this.grantId && !uuid(this.grantId))
    ) {
      this.error.set('Địa chỉ grant không hợp lệ.');
      return;
    }
    const epoch = this.epoch,
      actor = this.auth.user()?.id;
    this.loading.set(true);
    try {
      const w = await this.authorize(epoch, actor);
      if (!w) return;
      const p = this.correlation();
      if (this.unknown() && p) {
        const r = (
          await firstValueFrom(
            this.api.receipt(p.org, p.account, p.key, p.operation),
          )
        )?.data;
        if (!this.active(epoch, actor)) return;
        if (r !== null) {
          if (!this.accept(r, p)) {
            this.failure(null);
            return;
          }
          this.workspace.set(w);
          return;
        }
        this.notice.set(
          'Chưa tìm thấy biên nhận chính xác. Kết quả vẫn chưa xác định; không tạo thao tác thay thế.',
        );
        if (!this.pending) {
          this.workspace.set(w);
          this.loading.set(false);
          return;
        }
      }
      const r = this.grantId
        ? await firstValueFrom(
            this.api.revokeContext(this.id, this.accountId, this.grantId),
          )
        : await firstValueFrom(this.api.issueContext(this.id, this.accountId));
      if (!this.active(epoch, actor)) return;
      if (this.grantId) {
        if (!validRevoke(r?.data, this.id, this.accountId, this.grantId)) {
          this.failure(null);
          return;
        }
        this.revoke.set(r.data);
      } else {
        if (!validIssue(r?.data, this.id, this.accountId, actor!)) {
          this.failure(null);
          return;
        }
        this.issue.set(r.data);
      }
      this.workspace.set(w);
      this.loading.set(false);
      if (!this.grantId && !this.unknown()) void this.loadTeams();
      if (this.grantId) void this.loadHistory(0);
    } catch (e) {
      if (this.active(epoch, actor)) this.failure(e);
    }
  }
  async loadTeams(page = 0) {
    if (
      this.identityChecking() ||
      this.busy() ||
      this.unknown() ||
      this.grantId ||
      !this.issue() ||
      page < 0 ||
      page > 100000
    )
      return;
    const epoch = this.epoch,
      actor = this.auth.user()?.id,
      version = ++this.teamEpoch,
      query = reasonText(this.teamQuery);
    this.teams.set(null);
    this.teamLoading.set(true);
    if ([...query].length > 100) {
      this.teamLoading.set(false);
      this.error.set('Từ khóa team tối đa 100 ký tự.');
      return;
    }
    try {
      const w = await this.authorize(epoch, actor);
      if (!w || version !== this.teamEpoch) return;
      const r = await firstValueFrom(
        this.directory.list(this.id, page, query, ''),
      );
      if (!this.active(epoch, actor) || version !== this.teamEpoch) return;
      if (!validDirectory(r?.data, this.id, page)) {
        this.failure(null);
        return;
      }
      this.teams.set(r.data);
      this.teamPage = page;
      this.teamLoading.set(false);
    } catch (e) {
      if (this.active(epoch, actor) && version === this.teamEpoch)
        this.failure(e);
    }
  }
  chooseTeam(team: DirectoryItem) {
    if (
      this.identityChecking() ||
      this.unknown() ||
      this.busy() ||
      this.stage() !== 'edit' ||
      team.status === 'ARCHIVED' ||
      team.organizationId !== this.id
    )
      return;
    this.teamId = team.id;
    this.acknowledgement = false;
  }
  canReview() {
    return (
      !this.identityChecking() &&
      !this.busy() &&
      !this.loading() &&
      !this.unknown() &&
      validReason(this.reason) &&
      (this.grantId
        ? this.revoke()?.canRevoke === true
        : !!this.teamId &&
          risks.includes(this.risk as (typeof risks)[number]) &&
          /^\d+$/.test(this.duration) &&
          Number(this.duration) >= 1 &&
          Number(this.duration) <= 168)
    );
  }
  async review() {
    if (
      this.identityChecking() ||
      this.busy() ||
      this.loading() ||
      this.unknown() ||
      this.result() ||
      !validReason(this.reason)
    )
      return;
    const hours = Number(this.duration);
    if (
      !this.grantId &&
      (!this.teamId ||
        !risks.includes(this.risk as (typeof risks)[number]) ||
        !/^\d+$/.test(this.duration) ||
        hours < 1 ||
        hours > 168)
    )
      return;
    const captured = {
      team: this.teamId,
      risk: this.risk,
      duration: this.duration,
      reason: reasonText(this.reason),
    };
    const oldRevision = this.issue()?.revision ?? this.revoke()?.revision;
    this.clearPrivate();
    this.denied.set(false);
    this.busy.set(true);
    const epoch = this.epoch,
      actor = this.auth.user()?.id;
    try {
      const w = await this.authorize(epoch, actor);
      if (!w) return;
      const r = this.grantId
        ? await firstValueFrom(
            this.api.revokeContext(this.id, this.accountId, this.grantId),
          )
        : await firstValueFrom(
            this.api.issueContext(
              this.id,
              this.accountId,
              captured.team,
              captured.risk,
            ),
          );
      if (!this.active(epoch, actor)) return;
      if (this.grantId) {
        if (!validRevoke(r?.data, this.id, this.accountId, this.grantId)) {
          this.failure(null);
          return;
        }
        this.revoke.set(r.data);
      } else {
        if (
          !validIssue(
            r?.data,
            this.id,
            this.accountId,
            actor!,
            captured.team,
            captured.risk,
          )
        ) {
          this.failure(null);
          return;
        }
        this.issue.set(r.data);
      }
      this.workspace.set(w);
      this.teamId = captured.team;
      this.risk = captured.risk;
      this.duration = captured.duration;
      this.reason = captured.reason;
      this.busy.set(false);
      this.acknowledgement = false;
      if ((this.issue()?.canIssue ?? this.revoke()?.canRevoke) === true)
        this.stage.set('review');
      if (oldRevision !== (this.issue()?.revision ?? this.revoke()?.revision))
        this.notice.set(
          'Dữ liệu đã thay đổi. Hãy xem lại phiên bản mới trước khi xác nhận.',
        );
      if (this.grantId) void this.loadHistory(0);
      else if (this.stage() === 'edit') void this.loadTeams();
    } catch (e) {
      if (this.active(epoch, actor)) this.failure(e);
    }
  }
  edit() {
    if (this.busy() || this.unknown()) return;
    this.stage.set('edit');
    this.acknowledgement = false;
    if (!this.grantId) void this.loadTeams();
  }
  send() {
    if (
      this.identityChecking() ||
      this.busy() ||
      this.loading() ||
      this.unknown() ||
      this.pending ||
      this.unresolved ||
      this.result() ||
      this.stage() !== 'review' ||
      !this.acknowledgement ||
      !validReason(this.reason)
    )
      return;
    const actor = this.auth.user()?.id,
      d = this.issue(),
      r = this.revoke();
    if (
      !actor ||
      (!this.grantId &&
        (!d?.canIssue ||
          !d.selection ||
          d.selection.teamId !== this.teamId ||
          d.selection.riskLevel !== this.risk)) ||
      (this.grantId && !r?.canRevoke)
    )
      return;
    const revision = d?.revision ?? r!.revision;
    const body: Command = this.grantId
      ? {
          expectedRevision: revision,
          grantFingerprint: r!.grantFingerprint,
          reason: reasonText(this.reason),
          acknowledgement: true,
        }
      : {
          expectedRevision: revision,
          teamId: this.teamId,
          actionType: 'PUBLISH_PREVIEW',
          riskLevel: this.risk,
          durationHours: Number(this.duration),
          reason: reasonText(this.reason),
          acknowledgement: true,
        };
    this.pending = {
      org: this.id,
      account: this.accountId,
      actor,
      grant: this.grantId,
      operation: this.operation,
      key: crypto.randomUUID(),
      revision,
      body: Object.freeze(body),
      ambiguous: false,
    };
    this.transmit();
  }
  canRetry() {
    return (
      !this.identityChecking() &&
      !!this.pending &&
      this.unknown() &&
      !this.denied() &&
      !!this.workspace() &&
      this.pending.actor === this.auth.user()?.id &&
      this.pending.org === this.id &&
      this.pending.account === this.accountId &&
      this.pending.grant === this.grantId
    );
  }
  retry() {
    if (this.canRetry() && !this.busy() && !this.loading()) this.transmit();
  }
  private accept(value: unknown, p: Correlation) {
    if (
      !validResult(
        value,
        p.org,
        p.account,
        p.actor,
        p.key,
        p.operation,
        p.revision,
        p.grant || undefined,
      )
    )
      return false;
    if (
      this.pending &&
      p.operation === 'ISSUE' &&
      'durationHours' in this.pending.body &&
      stamp(value.expiresAt)! - stamp(value.recordedAt)! !==
        BigInt(this.pending.body.durationHours) * 3600000000000n
    )
      return false;
    this.pending = null;
    this.unresolved = null;
    this.unknown.set(false);
    this.reason = '';
    this.acknowledgement = false;
    this.result.set(value);
    this.loading.set(false);
    this.busy.set(false);
    this.notice.set(
      'Thao tác đã được ghi nhận. Biên nhận là lịch sử thao tác; hãy xem trạng thái grant hiện tại trong danh sách.',
    );
    return true;
  }
  private transmit() {
    const p = this.pending;
    if (
      !p ||
      this.identityChecking() ||
      this.busy() ||
      this.denied() ||
      p.actor !== this.auth.user()?.id ||
      p.org !== this.id ||
      p.account !== this.accountId ||
      p.grant !== this.grantId
    )
      return;
    const epoch = this.epoch,
      actor = p.actor;
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.requests.add(
      this.api.send(p.org, p.account, p.grant, p.key, p.body).subscribe({
        next: r => {
          if (!this.active(epoch, actor)) return;
          this.busy.set(false);
          if (!this.accept(r?.data, p)) {
            p.ambiguous = true;
            this.unknown.set(true);
            this.notice.set(
              'Không thể xác thực phản hồi. Kết quả chưa xác định; chỉ kiểm tra biên nhận hoặc thử lại đúng thao tác.',
            );
          }
        },
        error: e => {
          if (!this.active(epoch, actor)) return;
          this.busy.set(false);
          if (
            e instanceof HttpErrorResponse &&
            [400, 401, 403, 404, 409].includes(e.status)
          ) {
            if ([401, 403, 404].includes(e.status)) {
              this.deny();
              return;
            }
            if (p.ambiguous) {
              this.unresolved = this.opaque(p);
              this.pending = null;
              this.clearPrivate();
              this.unknown.set(true);
              this.notice.set(
                'Lần thử lại bị từ chối nhưng kết quả cũ vẫn chưa xác định. Hãy kiểm tra biên nhận chính xác.',
              );
            } else {
              this.pending = null;
              this.clearPrivate();
              this.error.set(
                'Thao tác bị từ chối do dữ liệu không hợp lệ hoặc đã thay đổi. Tải lại và xem lại trước khi gửi.',
              );
            }
          } else {
            p.ambiguous = true;
            this.unknown.set(true);
            this.notice.set(
              'Kết quả chưa xác định. Không tạo thao tác thay thế; kiểm tra biên nhận hoặc thử lại đúng thao tác cũ.',
            );
          }
        },
      }),
    );
  }
  async loadHistory(page: number) {
    if (
      this.identityChecking() ||
      !this.grantId ||
      !this.revoke() ||
      this.busy() ||
      page < 0 ||
      page > 100000
    )
      return;
    const epoch = this.epoch,
      actor = this.auth.user()?.id,
      version = ++this.historyEpoch;
    this.history.set(null);
    this.historyLoading.set(true);
    try {
      const w = await this.authorize(epoch, actor);
      if (!w || version !== this.historyEpoch) return;
      const r = await firstValueFrom(
        this.api.history(this.id, this.accountId, this.grantId, page),
      );
      if (!this.active(epoch, actor) || version !== this.historyEpoch) return;
      if (!validHistory(r?.data, this.id, this.accountId, this.grantId, page)) {
        this.failure(null);
        return;
      }
      this.history.set(r.data);
      this.historyPage = page;
      this.historyLoading.set(false);
    } catch (e) {
      if (this.active(epoch, actor) && version === this.historyEpoch)
        this.failure(e);
    }
  }
  blocker(code: string) {
    const labels: Record<string, string> = {
      SELF_ISSUANCE: 'Không được tự cấp grant.',
      RECIPIENT_DISABLED: 'Tài khoản nhận đã bị vô hiệu hóa.',
      TEAM_ARCHIVED: 'Team đã lưu trữ.',
      DUPLICATE_SCOPE: 'Đã có grant chưa hết hạn với đúng phạm vi này.',
      ALREADY_REVOKED: 'Grant đã được thu hồi.',
    };
    return labels[code] || code;
  }
  private dirty() {
    return (
      this.busy() ||
      this.unknown() ||
      !!this.pending ||
      !!this.unresolved ||
      !!this.teamId ||
      !!this.risk ||
      !!this.duration ||
      !!reasonText(this.reason)
    );
  }
  @HostListener('window:focus') async revalidateIdentity() {
    if (this.identityChecking() || this.destroy.destroyed) return;
    this.identityChecking.set(true);
    const actor = this.auth.user()?.id;
    const pending = this.pending;
    const org = this.id;
    const interrupted =
      this.loading() ||
      this.busy() ||
      this.teamLoading() ||
      this.historyLoading();
    if (pending) {
      // Dispatched writes can commit even after cancellation. Keep exact recovery
      // while invalidating every old callback before checking a shared session.
      pending.ambiguous = true;
      this.clearPrivate();
      this.unknown.set(true);
    }
    const epoch = this.epoch;
    try {
      const user = await firstValueFrom(
        from(this.auth.session()).pipe(timeout(30000)),
      );
      if (this.destroy.destroyed) return;
      if (user.id !== actor || this.auth.user()?.id !== actor) {
        this.identityChecking.set(false);
        this.reset();
        this.denied.set(true);
        return;
      }
      const checkedOrg = this.id;
      const w = (await firstValueFrom(this.api.workspace(checkedOrg)))?.data;
      if (this.destroy.destroyed) return;
      if (this.auth.user()?.id !== actor) {
        this.identityChecking.set(false);
        this.reset();
        this.denied.set(true);
        return;
      }
      if (this.id !== checkedOrg) {
        this.identityChecking.set(false);
        this.clearPrivate();
        void this.load();
        return;
      }
      if (
        !exact(w, 'id name role') ||
        w?.id !== checkedOrg ||
        w?.role !== 'ADMIN'
      ) {
        this.identityChecking.set(false);
        this.deny();
        return;
      }
      this.identityChecking.set(false);
      if (pending || interrupted || epoch !== this.epoch || org !== this.id) {
        this.clearPrivate();
        void this.load();
      }
    } catch {
      this.identityChecking.set(false);
      if (!this.destroy.destroyed) this.deny();
    }
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(
    e: BeforeUnloadEvent,
  ) {
    if (!this.result() && this.dirty()) e.preventDefault();
  }
  canLeave() {
    if (this.result() || !this.dirty()) return true;
    const id = this.router.getCurrentNavigation()?.id;
    if (id && id === this.allowedNavigation) return true;
    const leave = window.confirm(
      this.unknown()
        ? 'Kết quả thao tác chưa xác định. Rời trang có thể làm mất khóa kiểm tra biên nhận. Bạn vẫn muốn rời trang?'
        : 'Rời trang và bỏ nội dung grant chưa lưu?',
    );
    if (leave && id) this.allowedNavigation = id;
    return leave;
  }
}

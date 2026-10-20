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
import {ActivatedRoute, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {
  ActivationReview,
  ActivationReviewService,
  activationCheckCodes,
} from './activation-review.service';

@Component({
  selector: 'app-activation-review-page',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './activation-review-page.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../teams/team-page.component.css',
    './activation-review-page.component.css',
  ],
})
export class ActivationReviewPageComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ActivationReviewService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  id = '';
  agentId = '';
  readonly context = signal<Workspace | null>(null);
  readonly review = signal<ActivationReview | null>(null);
  readonly loading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly menuOpen = signal(false);

  constructor() {
    effect(() => {
      const account = this.auth.user()?.id;
      if (account !== this.account) {
        this.account = account;
        this.revoke();
      }
    });
    this.destroy.onDestroy(() => this.clear());
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.clear();
      this.id = params.get('id') || '';
      this.agentId = params.get('agentId') || '';
      void this.load();
    });
  }
  private clear(): void {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.review.set(null);
    this.context.set(null);
    this.loading.set(false);
    this.error.set('');
    this.menuOpen.set(false);
  }
  private revoke(): void {
    this.clear();
    this.denied.set(true);
  }
  async load(): Promise<void> {
    this.clear();
    const epoch = this.epoch;
    const account = this.auth.user()?.id;
    this.loading.set(true);
    this.denied.set(false);
    try {
      const context = await this.auth.getWorkspace(this.id);
      if (epoch !== this.epoch || this.destroy.destroyed) return;
      if (
        !account ||
        account !== this.auth.user()?.id ||
        context.id !== this.id ||
        context.role !== 'ADMIN'
      ) {
        this.revoke();
        return;
      }
      this.context.set(context);
      this.requests.add(
        this.api.get(this.id, this.agentId).subscribe({
          next: response => {
            const data = response?.data;
            if (epoch !== this.epoch || this.destroy.destroyed) return;
            if (account !== this.auth.user()?.id) {
              this.revoke();
              return;
            }
            this.loading.set(false);
            if (!this.valid(data)) {
              this.error.set(
                'Invalid activation review response. Check again to load the current review.',
              );
              return;
            }
            this.review.set(data);
          },
          error: error => {
            if (epoch === this.epoch) this.failure(error);
          },
        }),
      );
    } catch (error) {
      if (epoch === this.epoch) this.failure(error);
    }
  }
  private valid(data: ActivationReview | undefined): data is ActivationReview {
    return (
      !!data &&
      data.organizationId === this.id &&
      data.agentId === this.agentId &&
      typeof data.name === 'string' &&
      typeof data.status === 'string' &&
      Number.isInteger(data.revision) &&
      data.revision >= 0 &&
      data.revision <= 2147483647 &&
      typeof data.checkedAt === 'string' &&
      Number.isFinite(Date.parse(data.checkedAt)) &&
      typeof data.activationAllowed === 'boolean' &&
      Array.isArray(data.checks) &&
      data.checks.length === activationCheckCodes.length &&
      data.checks.every(
        (check, index) =>
          !!check &&
          check.code === activationCheckCodes[index] &&
          ['PASS', 'BLOCKED', 'UNAVAILABLE'].includes(check.status) &&
          typeof check.label === 'string' &&
          typeof check.message === 'string',
      ) &&
      data.activationAllowed ===
        data.checks.every(check => check.status === 'PASS')
    );
  }
  private failure(error: unknown): void {
    this.review.set(null);
    this.context.set(null);
    this.loading.set(false);
    if (
      (error instanceof HttpErrorResponse &&
        [401, 403, 404].includes(error.status)) ||
      (error instanceof AuthFailure &&
        ['unauthorized', 'not-found'].includes(error.kind))
    ) {
      this.revoke();
      return;
    }
    this.error.set('Không thể tải dữ liệu. Thử lại chỉ tải kiểm tra hiện tại.');
  }
  toggleMenu(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#activation-menu')
      ?.showModal();
    this.element.nativeElement
      .querySelector<HTMLElement>('#activation-menu a')
      ?.focus();
  }
  @HostListener('document:keydown.escape') closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#activation-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="activation-menu"]')
      ?.focus();
  }
}

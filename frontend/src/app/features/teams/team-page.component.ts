import {HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  effect,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {
  Candidate,
  CandidatePage,
  TeamBody,
  TeamDetail,
  TeamService,
  uuid,
  validTeam,
} from './team.service';

@Component({
  selector: 'app-team-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './team-page.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './team-page.component.css',
  ],
})
export class TeamPageComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(TeamService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private finished = false;
  private version = 0;
  private candidateVersion = 0;
  private requests = new Subscription();
  private candidateRequest?: Subscription;
  private attempt: {key: string; body: TeamBody} | null = null;
  private identity = this.auth.user()?.id;
  id = '';
  teamId = '';
  readonly context = signal<Workspace | null>(null);
  readonly loading = signal(true);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly saved = signal<TeamDetail | null>(null);
  readonly busy = signal(false);
  readonly frozen = signal(false);
  readonly keyConflict = signal(false);
  readonly candidates = signal<CandidatePage | null>(null);
  readonly candidateLoading = signal(false);
  readonly candidateError = signal('');
  readonly known = signal<Record<string, Candidate>>({});
  readonly menuOpen = signal(false);
  step = 1;
  name = '';
  purpose = '';
  description = '';
  manager = '';
  intake = 'MANUAL';
  concurrency = '';
  budget = '';
  query = '';
  selected: string[] = [];
  errors: Record<string, string> = {};
  readonly steps = ['Basics', 'Setup', 'Review'];
  constructor() {
    effect(() => {
      const identity = this.auth.user()?.id;
      if (identity !== this.identity) {
        this.identity = identity;
        this.clear();
        this.context.set(null);
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => this.clear());
  }
  ngOnInit(): void {
    this.route.paramMap
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe(params => {
        this.clear();
        this.id = params.get('id') || '';
        this.teamId = params.get('teamId') || '';
        void this.load();
      });
  }
  private clear(): void {
    this.finished = false;
    this.version++;
    this.candidateVersion++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.candidateRequest?.unsubscribe();
    this.name =
      this.purpose =
      this.description =
      this.manager =
      this.concurrency =
      this.budget =
      this.query =
        '';
    this.intake = 'MANUAL';
    this.selected = [];
    this.step = 1;
    this.errors = {};
    this.attempt = null;
    this.known.set({});
    this.candidates.set(null);
    this.candidateLoading.set(false);
    this.candidateError.set('');
    this.saved.set(null);
    this.busy.set(false);
    this.frozen.set(false);
    this.keyConflict.set(false);
    this.menuOpen.set(false);
  }
  async load(): Promise<void> {
    const version = ++this.version;
    const account = this.auth.user()?.id;
    const active = () =>
      version === this.version &&
      !this.destroy.destroyed &&
      account === this.auth.user()?.id;
    this.loading.set(true);
    this.context.set(null);
    this.error.set('');
    this.denied.set(false);
    try {
      const context = await this.auth.getWorkspace(this.id);
      if (!active()) return;
      this.context.set(context);
      this.loading.set(false);
      if (this.teamId) this.loadDetail();
      else if (context.role !== 'ADMIN') this.revoke();
      else this.loadCandidates();
    } catch (error) {
      if (!active()) return;
      this.loading.set(false);
      this.handleError(error);
    }
  }
  private revoke(): void {
    this.clear();
    this.context.set(null);
    this.denied.set(true);
    this.loading.set(false);
    this.error.set('');
  }
  private handleError(error: unknown): void {
    const status = error instanceof HttpErrorResponse ? error.status : 0;
    if (
      status === 403 ||
      status === 404 ||
      (error instanceof AuthFailure && error.kind === 'not-found')
    )
      this.revoke();
    else if (
      status === 401 ||
      (error instanceof AuthFailure && error.kind === 'unauthorized')
    ) {
      this.revoke();
      void this.router.navigateByUrl('/login');
    } else
      this.error.set('Không thể tải dữ liệu. Kiểm tra kết nối và thử lại.');
  }
  loadDetail(): void {
    if (!this.context() || !uuid(this.teamId)) {
      this.revoke();
      return;
    }
    const version = ++this.version;
    this.loading.set(true);
    this.saved.set(null);
    this.error.set('');
    this.requests.add(
      this.api.detail(this.id, this.teamId).subscribe({
        next: response => {
          const data = response?.data;
          if (version !== this.version) return;
          this.loading.set(false);
          if (
            !data ||
            data.id !== this.teamId ||
            data.organizationId !== this.id ||
            !uuid(data.id)
          ) {
            this.error.set('Phản hồi team không hợp lệ. Vui lòng thử lại.');
            return;
          }
          this.saved.set(data);
        },
        error: error => {
          if (version === this.version) {
            this.loading.set(false);
            this.handleError(error);
          }
        },
      }),
    );
  }
  loadCandidates(page = 0): void {
    if (
      this.denied() ||
      this.teamId ||
      this.context()?.role !== 'ADMIN' ||
      this.frozen()
    )
      return;
    const version = ++this.candidateVersion;
    const scope = this.version;
    this.candidateRequest?.unsubscribe();
    this.candidateLoading.set(true);
    this.candidateError.set('');
    this.candidates.set(null);
    this.candidateRequest = this.api
      .candidates(this.id, page, this.query.trim().slice(0, 100))
      .subscribe({
        next: response => {
          const data = response?.data;
          if (version !== this.candidateVersion || scope !== this.version)
            return;
          this.candidateLoading.set(false);
          if (!data || !Array.isArray(data.items) || data.size <= 0) {
            this.candidateError.set('Không thể tải agent.');
            return;
          }
          const items = data.items.filter(
            item => uuid(item.id) && ['DRAFT', 'ACTIVE'].includes(item.status),
          );
          this.candidates.set({...data, items});
          this.known.update(known => ({
            ...known,
            ...Object.fromEntries(items.map(item => [item.id, item])),
          }));
        },
        error: error => {
          if (version !== this.candidateVersion || scope !== this.version)
            return;
          this.candidateLoading.set(false);
          if (
            error instanceof HttpErrorResponse &&
            [401, 403].includes(error.status)
          )
            this.handleError(error);
          else
            this.candidateError.set('Không thể tải agent. Vui lòng thử lại.');
        },
      });
  }
  managers(): Candidate[] {
    return Object.values(this.known()).filter(
      agent => agent.level === 'MANAGER',
    );
  }
  agentName(id: string): string {
    return this.known()[id]?.name || id;
  }
  changeManager(value: string): void {
    this.manager = value;
    this.selected = this.selected.filter(id => id !== value);
  }
  toggle(id: string, checked: boolean): void {
    if (this.frozen() || id === this.manager) return;
    this.selected = checked
      ? [...new Set([...this.selected, id])]
      : this.selected.filter(value => value !== id);
  }
  validate(): boolean {
    this.errors = {};
    if (this.step === 1) {
      if (!this.name.trim() || this.name.trim().length > 255)
        this.errors['name'] = 'Tên team bắt buộc, tối đa 255 ký tự.';
      if (!this.purpose.trim() || this.purpose.trim().length > 2000)
        this.errors['purpose'] = 'Mục đích team bắt buộc, tối đa 2000 ký tự.';
      if (this.description.length > 4000)
        this.errors['description'] = 'Tối đa 4000 ký tự.';
    } else {
      if (
        !/^\d+$/.test(String(this.concurrency)) ||
        +this.concurrency < 1 ||
        +this.concurrency > 1000
      )
        this.errors['concurrency'] = 'Nhập số nguyên từ 1 đến 1000.';
      if (
        !/^\d+(\.\d{1,2})?$/.test(String(this.budget)) ||
        +this.budget > 1000000
      )
        this.errors['budget'] =
          'Nhập ngân sách từ 0 đến 1000000 USD, tối đa 2 chữ số thập phân.';
      if (this.selected.length > 50)
        this.errors['agents'] = 'Chọn tối đa 50 agent.';
    }
    if (Object.keys(this.errors).length)
      setTimeout(() =>
        this.element.nativeElement
          .querySelector<HTMLElement>('[aria-invalid="true"]')
          ?.focus(),
      );
    return !Object.keys(this.errors).length;
  }
  next(): void {
    if (!this.frozen() && this.step < 3 && this.validate()) {
      this.step++;
      this.error.set('');
    }
  }
  back(): void {
    if (!this.frozen() && !this.busy() && this.step > 1) {
      this.step--;
      this.errors = {};
      this.error.set('');
    }
  }
  private hasUnsavedChanges(): boolean {
    return (
      !this.finished &&
      !this.teamId &&
      !this.denied() &&
      !!(
        this.name ||
        this.purpose ||
        this.description ||
        this.manager ||
        this.concurrency ||
        this.budget ||
        this.selected.length ||
        this.attempt
      )
    );
  }
  canLeave(): boolean {
    return (
      !this.hasUnsavedChanges() ||
      window.confirm('Bạn có muốn rời trang và bỏ các thay đổi chưa lưu?')
    );
  }
  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.hasUnsavedChanges()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
  cancel(): void {
    if (
      !this.busy() &&
      window.confirm('Bạn có muốn bỏ các thay đổi chưa lưu?')
    ) {
      this.clear();
      void this.router.navigate(['/workspace', this.id, 'dashboard']);
    }
  }
  create(): void {
    if (
      this.step !== 3 ||
      this.busy() ||
      this.keyConflict() ||
      this.denied() ||
      this.context()?.role !== 'ADMIN'
    )
      return;
    if (!this.attempt) {
      if (!this.validate()) {
        this.step = 2;
        return;
      }
      const body: TeamBody = {
        name: this.name.trim(),
        purpose: this.purpose.trim(),
        description: this.description,
        managerAgentId: this.manager || null,
        agentIds: [...this.selected].sort(),
        intake: 'MANUAL',
        concurrency: +this.concurrency,
        monthlyBudget: +this.budget,
      };
      Object.freeze(body.agentIds);
      Object.freeze(body);
      this.attempt = {key: crypto.randomUUID(), body};
    }
    this.frozen.set(true);
    this.busy.set(true);
    this.error.set('');
    const version = this.version;
    this.requests.add(
      this.api.create(this.id, this.attempt.body, this.attempt.key).subscribe({
        next: response => {
          const data = response?.data;
          if (version !== this.version) return;
          this.busy.set(false);
          if (!validTeam(data, this.id)) {
            this.error.set(
              'Chưa xác nhận được kết quả. Nhấn Create draft để thử lại cùng yêu cầu.',
            );
            return;
          }
          this.finished = true;
          void this.router.navigate(['/workspace', this.id, 'teams', data.id]);
        },
        error: error => {
          if (version !== this.version) return;
          this.busy.set(false);
          if (error instanceof HttpErrorResponse) {
            const code = error.error?.error?.code;
            if ([401, 403].includes(error.status)) {
              this.handleError(error);
              return;
            }
            if (error.status === 409 && code === 'IDEMPOTENCY_CONFLICT') {
              this.keyConflict.set(true);
              this.error.set(
                'Không thể xác nhận yêu cầu vì thông tin không khớp. Yêu cầu đã được khóa; vui lòng liên hệ quản trị viên.',
              );
              return;
            }
            if (
              error.status === 400 ||
              (error.status === 409 && code === 'TEAM_SETUP_CONFLICT')
            ) {
              this.attempt = null;
              this.frozen.set(false);
              this.step = error.status === 400 ? 1 : 2;
              this.error.set(
                code === 'TEAM_SETUP_CONFLICT'
                  ? 'Agent không còn khả dụng. Vui lòng chọn lại cấu hình.'
                  : 'Dữ liệu không hợp lệ. Kiểm tra các trường và thử lại.',
              );
              this.loadCandidates();
              return;
            }
          }
          this.error.set(
            'Chưa xác nhận được kết quả. Nhấn Create draft để thử lại cùng yêu cầu.',
          );
        },
      }),
    );
  }
}

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
import {uuid} from '../teams/team.service';
import {
  EligibleTeam,
  EligibleTeamPage,
  AgentBody,
  AgentDetail,
  AgentService,
  validAgent,
} from './agent.service';

@Component({
  selector: 'app-agent-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './agent-page.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../teams/team-page.component.css',
    './agent-page.component.css',
  ],
})
export class AgentPageComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(AgentService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private finished = false;
  private version = 0;
  private candidateVersion = 0;
  private requests = new Subscription();
  private candidateRequest?: Subscription;
  private attempt: {key: string; body: AgentBody} | null = null;
  private identity = this.auth.user()?.id;
  id = '';
  agentId = '';
  readonly context = signal<Workspace | null>(null);
  readonly loading = signal(true);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly saved = signal<AgentDetail | null>(null);
  readonly busy = signal(false);
  readonly frozen = signal(false);
  readonly keyConflict = signal(false);
  readonly candidates = signal<EligibleTeamPage | null>(null);
  readonly candidateLoading = signal(false);
  readonly candidateError = signal('');
  readonly known = signal<Record<string, EligibleTeam>>({});
  readonly menuOpen = signal(false);
  step = 1;
  values: Record<string, string> = {};
  team = '';
  query = '';
  private prefill = '';
  readonly prefillNotice = signal('');
  errors: Record<string, string> = {};
  readonly steps = ['Identity', 'Contract', 'Capabilities', 'Review & test'];
  readonly fields = [
    {key: 'name', label: 'Agent name', step: 1, max: 255, required: true},
    {key: 'role', label: 'Role', step: 1, max: 255, required: true},
    {
      key: 'description',
      label: 'Description',
      step: 1,
      max: 4000,
      required: false,
    },
    {
      key: 'responsibilities',
      label: 'Responsibilities',
      step: 2,
      max: 4000,
      required: true,
    },
    {
      key: 'expectedInput',
      label: 'Expected input',
      step: 2,
      max: 4000,
      required: true,
    },
    {
      key: 'outputFormat',
      label: 'Output format',
      step: 2,
      max: 4000,
      required: true,
    },
    {
      key: 'acceptanceCriteria',
      label: 'Acceptance criteria',
      step: 2,
      max: 4000,
      required: true,
    },
    {key: 'model', label: 'Model', step: 3, max: 2000, required: false},
    {key: 'skills', label: 'Skills', step: 3, max: 2000, required: false},
    {
      key: 'tools',
      label: 'Tools / requested scope',
      step: 3,
      max: 2000,
      required: false,
    },
    {
      key: 'limits',
      label: 'Limits (requested)',
      step: 3,
      max: 2000,
      required: false,
    },
  ];
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
        this.agentId = params.get('agentId') || '';
        this.prefill = this.route.snapshot.queryParamMap.get('teamId') || '';
        if (this.prefill)
          this.prefillNotice.set(
            'Team chưa được xác nhận. Chọn team sau khi tải danh sách hợp lệ.',
          );
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
    this.values = {};
    this.team = this.query = this.prefill = '';
    this.prefillNotice.set('');
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
      if (this.agentId) this.loadDetail();
      else if (context.role !== 'ADMIN') this.revoke();
      else this.loadEligibleTeams();
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
    if (!this.context() || !uuid(this.agentId)) {
      this.revoke();
      return;
    }
    const version = ++this.version;
    this.loading.set(true);
    this.saved.set(null);
    this.error.set('');
    this.requests.add(
      this.api.detail(this.id, this.agentId).subscribe({
        next: response => {
          const data = response?.data;
          if (version !== this.version) return;
          this.loading.set(false);
          if (
            !data ||
            data.id !== this.agentId ||
            data.organizationId !== this.id ||
            !uuid(data.id)
          ) {
            this.error.set('Phản hồi agent không hợp lệ. Vui lòng thử lại.');
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
  loadEligibleTeams(page = 0): void {
    if (
      this.denied() ||
      this.agentId ||
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
      .candidates(this.id, page, this.query.slice(0, 100))
      .subscribe({
        next: response => {
          const data = response?.data;
          if (version !== this.candidateVersion || scope !== this.version)
            return;
          this.candidateLoading.set(false);
          if (!data || !Array.isArray(data.items) || data.size <= 0) {
            this.candidateError.set('Không thể tải team.');
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
          if (
            this.prefill &&
            this.step === 1 &&
            !this.frozen() &&
            !this.busy()
          ) {
            const match = items.find(item => item.id === this.prefill);
            if (match) {
              this.team = match.id;
              this.prefill = '';
              this.prefillNotice.set('');
            } else
              this.prefillNotice.set(
                'Team trong liên kết chưa được xác nhận. Tìm và chọn team hợp lệ hoặc Thiết lập sau.',
              );
          }
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
          else this.candidateError.set('Không thể tải team. Vui lòng thử lại.');
        },
      });
  }
  savedValue(key: string): string {
    const saved = this.saved();
    return saved ? String(saved[key as keyof AgentBody] ?? '—') : '—';
  }
  reviewValue(key: string): string {
    const value = this.values[key] || '';
    return (
      (this.fields.find(field => field.key === key)?.required
        ? value.trim()
        : value) || '—'
    );
  }
  teamOptions(): EligibleTeam[] {
    const items = this.candidates()?.items || [];
    const selected = this.known()[this.team];
    return selected && !items.some(item => item.id === selected.id)
      ? [selected, ...items]
      : items;
  }
  teamName(): string {
    return this.known()[this.team]?.name || this.team || 'Thiết lập sau';
  }
  chooseTeam(value: string): void {
    if (this.frozen() || this.busy()) return;
    this.team = value && this.known()[value] ? value : '';
    this.prefill = '';
    this.prefillNotice.set('');
  }
  validate(all = false): boolean {
    this.errors = {};
    let firstStep = 0;
    for (const field of this.fields) {
      if (!all && field.step !== this.step) continue;
      const raw = this.values[field.key] || '';
      const value = field.required ? raw.trim() : raw;
      if ((field.required && !value) || value.length > field.max) {
        this.errors[field.key] =
          `${field.label}${field.required ? ' bắt buộc,' : ':'} tối đa ${field.max} ký tự.`;
        firstStep ||= field.step;
      }
    }
    if (this.team && !this.known()[this.team]) {
      this.errors['team'] = 'Chọn team hợp lệ hoặc Thiết lập sau.';
      firstStep ||= 1;
    }
    if (Object.keys(this.errors).length) {
      if (all) this.step = firstStep;
      setTimeout(() => {
        if (!this.destroy.destroyed)
          this.element.nativeElement
            .querySelector<HTMLElement>('[aria-invalid="true"]')
            ?.focus();
      });
    }
    return !Object.keys(this.errors).length;
  }
  next(): void {
    if (!this.frozen() && this.step < 4 && this.validate()) {
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
      !this.agentId &&
      !this.denied() &&
      !!(Object.values(this.values).some(Boolean) || this.team || this.attempt)
    );
  }
  canLeave(): boolean {
    return (
      !this.hasUnsavedChanges() ||
      window.confirm('Bạn có muốn rời trang và bỏ các thay đổi chưa lưu?')
    );
  }
  @HostListener('document:keydown.escape')
  closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="agent-menu"]')
      ?.focus();
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
      this.step !== 4 ||
      this.busy() ||
      this.keyConflict() ||
      this.denied() ||
      this.context()?.role !== 'ADMIN'
    )
      return;
    if (!this.attempt) {
      if (!this.validate(true)) return;
      const body: AgentBody = {
        name: (this.values['name'] || '').trim(),
        role: (this.values['role'] || '').trim(),
        teamId: this.team || null,
        description: this.values['description'] || null,
        responsibilities: (this.values['responsibilities'] || '').trim(),
        expectedInput: (this.values['expectedInput'] || '').trim(),
        outputFormat: (this.values['outputFormat'] || '').trim(),
        acceptanceCriteria: (this.values['acceptanceCriteria'] || '').trim(),
        model: this.values['model'] || null,
        skills: this.values['skills'] || null,
        tools: this.values['tools'] || null,
        limits: this.values['limits'] || null,
      };
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
          if (!validAgent(data, this.id)) {
            this.error.set(
              'Chưa xác nhận được kết quả. Nhấn Create draft để thử lại cùng yêu cầu.',
            );
            return;
          }
          this.finished = true;
          void this.router.navigate(['/workspace', this.id, 'agents', data.id]);
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
              (error.status === 409 && code === 'AGENT_SETUP_CONFLICT')
            ) {
              this.attempt = null;
              this.frozen.set(false);
              this.step = 1;
              if (code === 'AGENT_SETUP_CONFLICT') {
                this.team = '';
                this.known.set({});
              }
              this.error.set(
                code === 'AGENT_SETUP_CONFLICT'
                  ? 'Team không còn khả dụng. Vui lòng chọn lại team.'
                  : 'Dữ liệu không hợp lệ. Kiểm tra các trường và thử lại.',
              );
              this.loadEligibleTeams();
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

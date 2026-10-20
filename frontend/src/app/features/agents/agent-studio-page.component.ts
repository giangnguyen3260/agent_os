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
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, RouterLink} from '@angular/router';
import {Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {
  AgentStudioService,
  StudioBody,
  StudioConfiguration,
  StudioNumber,
  StudioText,
} from './agent-studio.service';

@Component({
  selector: 'app-agent-studio-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './agent-studio-page.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    '../teams/team-page.component.css',
    './agent-studio-page.component.css',
  ],
})
export class AgentStudioPageComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(AgentStudioService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  private attempt: {body: StudioBody; key: string} | null = null;
  id = '';
  agentId = '';
  readonly context = signal<Workspace | null>(null);
  readonly configuration = signal<StudioConfiguration | null>(null);
  readonly stage = signal<'view' | 'edit' | 'diff' | 'saved'>('view');
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly frozen = signal(false);
  readonly conflict = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly menuOpen = signal(false);
  values: Record<StudioText, string> = this.emptyText();
  numbers: Record<StudioNumber, number | null> = {
    concurrency: null,
    maxTokensPerRun: null,
    monthlyBudget: null,
  };
  errors: Record<string, string> = {};
  readonly fields: {key: StudioText; label: string; required: boolean}[] = [
    {key: 'responsibilities', label: 'Responsibilities', required: true},
    {key: 'expectedInput', label: 'Expected input', required: true},
    {key: 'outputFormat', label: 'Output format', required: true},
    {key: 'acceptanceCriteria', label: 'Acceptance criteria', required: true},
    {key: 'model', label: 'Model', required: false},
    {key: 'skills', label: 'Skills', required: false},
    {key: 'tools', label: 'Tools / requested scope', required: false},
    {key: 'limits', label: 'Limits (requested)', required: false},
  ];
  readonly numericFields: {
    key: StudioNumber;
    label: string;
    min: number;
    max: number;
    step: number;
  }[] = [
    {key: 'concurrency', label: 'Concurrency', min: 1, max: 1000, step: 1},
    {
      key: 'maxTokensPerRun',
      label: 'Max tokens per run',
      min: 1,
      max: 2000000,
      step: 1,
    },
    {
      key: 'monthlyBudget',
      label: 'Monthly budget (USD)',
      min: 0,
      max: 1000000,
      step: 0.01,
    },
  ];
  constructor() {
    effect(() => {
      const account = this.auth.user()?.id;
      if (account !== this.account) {
        this.account = account;
        this.clear();
        this.denied.set(true);
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
  private emptyText(): Record<StudioText, string> {
    return {
      responsibilities: '',
      expectedInput: '',
      outputFormat: '',
      acceptanceCriteria: '',
      model: '',
      skills: '',
      tools: '',
      limits: '',
    };
  }
  private clear(): void {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.configuration.set(null);
    this.context.set(null);
    this.values = this.emptyText();
    this.numbers = {
      concurrency: null,
      maxTokensPerRun: null,
      monthlyBudget: null,
    };
    this.attempt = null;
    this.errors = {};
    this.stage.set('view');
    this.frozen.set(false);
    this.busy.set(false);
    this.loading.set(false);
    this.conflict.set(false);
    this.error.set('');
    this.menuOpen.set(false);
  }
  editable(): boolean {
    return (
      this.context()?.role === 'ADMIN' &&
      this.configuration()?.agent.status === 'DRAFT' &&
      (this.configuration()?.revision ?? Infinity) < 2147483647
    );
  }
  heading(): string {
    return {
      view: 'Agent Studio',
      edit: 'Edit configuration',
      diff: 'Review configuration changes',
      saved: 'Configuration saved',
    }[this.stage()];
  }
  async load(): Promise<void> {
    if (this.frozen() || this.busy()) return;
    const epoch = ++this.epoch;
    this.loading.set(true);
    this.error.set('');
    this.denied.set(false);
    this.configuration.set(null);
    this.context.set(null);
    try {
      const context = await this.auth.getWorkspace(this.id);
      if (epoch !== this.epoch || this.destroy.destroyed) return;
      if (context.id !== this.id) {
        this.revoke();
        return;
      }
      this.context.set(context);
      this.requests.add(
        this.api.get(this.id, this.agentId).subscribe({
          next: ({data}) => {
            if (epoch !== this.epoch) return;
            this.loading.set(false);
            if (!this.valid(data)) {
              this.error.set('Invalid configuration response.');
              return;
            }
            this.configuration.set(data);
            this.populate();
            this.stage.set('view');
            this.conflict.set(false);
          },
          error: error => {
            if (epoch === this.epoch) {
              this.loading.set(false);
              this.failure(error);
            }
          },
        }),
      );
    } catch (error) {
      if (epoch === this.epoch) {
        this.loading.set(false);
        this.failure(error);
      }
    }
  }
  private valid(
    data: StudioConfiguration | undefined,
  ): data is StudioConfiguration {
    return (
      !!data &&
      data.agent?.id === this.agentId &&
      data.agent.organizationId === this.id &&
      Number.isInteger(data.revision) &&
      data.revision >= 0 &&
      data.revision <= 2147483647 &&
      data.currency === 'USD' &&
      this.numericFields.every(field => {
        const value = data[field.key];
        return (
          value === null ||
          (typeof value === 'number' && this.validNumber(value, field))
        );
      }) &&
      this.fields.every(
        field =>
          typeof data.agent[field.key] === 'string' ||
          data.agent[field.key] === null,
      )
    );
  }
  private validNumber(
    value: number,
    field: (typeof this.numericFields)[number],
  ): boolean {
    return (
      Number.isFinite(value) &&
      value >= field.min &&
      value <= field.max &&
      (field.step === 1
        ? Number.isInteger(value)
        : Math.abs(value * 100 - Math.round(value * 100)) < 0.000001)
    );
  }
  private revoke(): void {
    this.clear();
    this.denied.set(true);
  }
  private failure(error: unknown): void {
    if (
      (error instanceof HttpErrorResponse &&
        [401, 403, 404].includes(error.status)) ||
      (error instanceof AuthFailure &&
        ['unauthorized', 'not-found'].includes(error.kind))
    ) {
      this.revoke();
      return;
    }
    this.error.set('Không thể tải dữ liệu. Thử lại chỉ tải cấu hình hiện tại.');
  }
  private populate(): void {
    const data = this.configuration();
    if (!data) return;
    for (const field of this.fields)
      this.values[field.key] = data.agent[field.key] ?? '';
    for (const field of this.numericFields)
      this.numbers[field.key] = data[field.key];
    this.errors = {};
    this.attempt = null;
    this.frozen.set(false);
  }
  edit(): void {
    if (this.editable() && !this.loading()) {
      this.populate();
      this.error.set('');
      this.stage.set('edit');
    }
  }
  private body(): StudioBody {
    return {
      ...Object.fromEntries(
        this.fields.map(field => [
          field.key,
          field.required
            ? this.values[field.key].trim()
            : this.values[field.key] || null,
        ]),
      ),
      ...this.numbers,
      expectedRevision: this.configuration()!.revision,
    } as StudioBody;
  }
  changes(): {label: string; before: string; after: string}[] {
    const data = this.configuration();
    if (!data) return [];
    const body = this.body();
    return [...this.fields, ...this.numericFields].flatMap(field => {
      const before =
        field.key in data.agent
          ? data.agent[field.key as StudioText]
          : data[field.key as StudioNumber];
      const after = body[field.key];
      return (before ?? null) === after
        ? []
        : [
            {
              label: field.label,
              before: String(before ?? '—'),
              after: String(after ?? '—'),
            },
          ];
    });
  }
  review(): void {
    if (!this.editable() || this.frozen() || this.conflict()) return;
    this.errors = {};
    this.error.set('');
    for (const field of this.fields) {
      const value = field.required
        ? this.values[field.key].trim()
        : this.values[field.key];
      if (
        (field.required && !value) ||
        value.length > (field.required ? 4000 : 2000)
      )
        this.errors[field.key] =
          `${field.label}: ${field.required ? 'required; ' : ''}maximum ${field.required ? 4000 : 2000} characters.`;
    }
    for (const field of this.numericFields) {
      const value = this.numbers[field.key];
      const input = this.element.nativeElement.querySelector<HTMLInputElement>(
        `#${field.key}`,
      );
      if (
        input?.validity.badInput ||
        (value !== null &&
          (typeof value !== 'number' || !this.validNumber(value, field)))
      )
        this.errors[field.key] =
          `${field.label}: ${field.min}–${field.max}; ${field.step === 1 ? 'whole numbers only' : 'maximum 2 decimal places'}.`;
    }
    if (Object.keys(this.errors).length) {
      setTimeout(() =>
        this.element.nativeElement
          .querySelector<HTMLElement>('[aria-invalid="true"]')
          ?.focus(),
      );
      return;
    }
    if (!this.changes().length) {
      this.error.set('No configuration changes to review.');
      return;
    }
    this.stage.set('diff');
  }
  back(): void {
    if (!this.frozen() && !this.busy() && !this.conflict())
      this.stage.set('edit');
  }
  dirty(): boolean {
    return (
      !!this.attempt ||
      (['edit', 'diff'].includes(this.stage()) && this.changes().length > 0)
    );
  }
  canLeave(): boolean {
    return (
      !this.dirty() ||
      window.confirm(
        'Discard unsaved changes? An unknown save may already have committed.',
      )
    );
  }
  cancel(): void {
    if (!this.busy() && !this.frozen() && !this.conflict() && this.canLeave()) {
      this.populate();
      this.conflict.set(false);
      this.error.set('');
      this.stage.set('view');
    }
  }
  reload(): void {
    if (
      !this.busy() &&
      !this.frozen() &&
      (!this.dirty() ||
        window.confirm('Discard local edits and reload latest configuration?'))
    ) {
      this.populate();
      this.stage.set('view');
      void this.load();
    }
  }
  save(): void {
    if (
      this.stage() !== 'diff' ||
      !this.editable() ||
      this.busy() ||
      this.conflict()
    )
      return;
    if (!this.attempt)
      this.attempt = {
        body: Object.freeze(this.body()),
        key: crypto.randomUUID(),
      };
    const attempt = this.attempt;
    const epoch = this.epoch;
    this.frozen.set(true);
    this.busy.set(true);
    this.error.set('');
    this.requests.add(
      this.api
        .save(this.id, this.agentId, attempt.body, attempt.key)
        .subscribe({
          next: ({data}) => {
            if (epoch !== this.epoch) return;
            this.busy.set(false);
            if (
              !this.valid(data) ||
              data.agent.status !== 'DRAFT' ||
              data.revision !== attempt.body.expectedRevision + 1
            ) {
              this.error.set(
                'Unknown save outcome. Retry the identical request.',
              );
              return;
            }
            this.configuration.set(data);
            this.populate();
            this.stage.set('saved');
          },
          error: error => {
            if (epoch !== this.epoch) return;
            this.busy.set(false);
            if (error instanceof HttpErrorResponse) {
              if ([401, 403, 404].includes(error.status)) {
                this.revoke();
                return;
              }
              if (error.status === 409) {
                this.frozen.set(false);
                this.attempt = null;
                this.conflict.set(true);
                this.error.set(
                  `${error.error?.error?.code || 'CONFIGURATION_CONFLICT'}: local edits preserved. Reload latest to discard and inspect current configuration.`,
                );
                return;
              }
              if (error.status === 400) {
                this.frozen.set(false);
                this.attempt = null;
                this.stage.set('edit');
                this.error.set(
                  'Configuration rejected. Check fields and review again.',
                );
                return;
              }
            }
            this.error.set(
              'Unknown save outcome. Fields and revision are frozen; Thử lại sends the identical body and key.',
            );
          },
        }),
    );
  }
  @HostListener('document:keydown.escape') closeMenu(): void {
    if (this.menuOpen()) {
      this.menuOpen.set(false);
      this.element.nativeElement
        .querySelector<HTMLElement>('[aria-controls="studio-menu"]')
        ?.focus();
    }
  }
  @HostListener('window:beforeunload', ['$event']) beforeUnload(
    event: BeforeUnloadEvent,
  ): void {
    if (this.dirty()) {
      event.preventDefault();
      event.returnValue = '';
    }
  }
}

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
  TraceService,
  TracePage,
  TraceEvent,
  TraceFilters,
  traceUuid,
  validTracePage,
  validTraceDetail,
  validTraceWorkspace,
  stripTraceQuery,
} from './trace.service';
import {FormsModule} from '@angular/forms';
import {defaultIfEmpty, forkJoin, map, of} from 'rxjs';
@Component({
  selector: 'app-trace-explorer',
  standalone: true,
  imports: [RouterLink, FormsModule],
  templateUrl: './trace-explorer.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './trace-explorer.component.css',
  ],
})
export class TraceExplorerComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(TraceService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  id = '';
  readonly context = signal<Workspace | null>(null);
  readonly page = signal<TracePage | null>(null);
  readonly selected = signal<TraceEvent | null>(null);
  draft = {q: '', missionId: '', taskId: ''};
  filters: TraceFilters = {...this.draft, page: 0};
  readonly validation = signal('');
  readonly loading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly menuOpen = signal(false);
  constructor() {
    effect(() => {
      const actor = this.auth.user()?.id;
      if (actor !== this.account) {
        this.account = actor;
        this.clear();
        this.denied.set(true);
      }
    });
    this.destroy.onDestroy(() => this.clear());
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
      this.clear();
      this.id = params.get('id') || '';
      this.filters = {q: '', missionId: '', taskId: '', page: 0};
      void this.load();
    });
  }
  apply(): void {
    const q = stripTraceQuery(this.draft.q);
    if (
      Array.from(q).length > 100 ||
      [this.draft.missionId, this.draft.taskId].some(
        v => v !== '' && !traceUuid(v),
      )
    ) {
      this.validation.set(
        'Loại sự kiện tối đa 100 ký tự. Mission ID và Task ID phải là UUID hợp lệ.',
      );
      return;
    }
    this.validation.set('');
    this.filters = {...this.draft, q, page: 0};
    void this.load();
  }
  reset(): void {
    this.draft = {q: '', missionId: '', taskId: ''};
    this.filters = {...this.draft, page: 0};
    this.validation.set('');
    void this.load();
  }
  changePage(delta: number): void {
    const page = this.page();
    if (!page || ![-1, 1].includes(delta)) return;
    const next = page.page + delta;
    if (
      next < 0 ||
      next > 100000 ||
      (delta > 0 && next * page.size >= page.total)
    )
      return;
    this.filters = {...this.filters, page: next};
    void this.load();
  }
  select(eventId: string): void {
    void this.load(eventId);
  }
  private clear(): void {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.page.set(null);
    this.selected.set(null);
    this.context.set(null);
    this.loading.set(false);
    this.error.set('');
    this.closeMenu();
  }
  private active(epoch: number, actor: string | undefined): boolean {
    return (
      epoch === this.epoch &&
      !this.destroy.destroyed &&
      !!actor &&
      actor === this.auth.user()?.id
    );
  }
  @HostListener('window:focus')
  revalidate(): Promise<void> {
    return this.load();
  }
  @HostListener('document:visibilitychange')
  onVisibilityChange(): void {
    if (document.visibilityState === 'visible') void this.load();
  }
  async load(eventId = ''): Promise<void> {
    this.clear();
    this.denied.set(false);
    if (!traceUuid(this.id) || (eventId !== '' && !traceUuid(eventId))) {
      this.error.set('Đường dẫn không hợp lệ.');
      return;
    }
    const epoch = this.epoch;
    const org = this.id;
    const filters = {...this.filters};
    const actor = this.auth.user()?.id;
    if (!actor) {
      this.denied.set(true);
      return;
    }
    this.loading.set(true);
    try {
      const session = await this.auth.session();
      if (!this.active(epoch, actor)) return;
      if (session.id !== actor) {
        this.clear();
        this.denied.set(true);
        return;
      }
      const workspace = await this.api.workspace(org);
      if (!this.active(epoch, actor)) return;
      if (!validTraceWorkspace(workspace?.data, org)) {
        this.clear();
        this.error.set('Dữ liệu workspace không hợp lệ. Vui lòng thử lại.');
        return;
      }
      const context = workspace.data;
      this.requests.add(
        forkJoin({
          list: this.api.list(org, filters).pipe(
            defaultIfEmpty(undefined),
            map(response => {
              if (!validTracePage(response?.data, org, filters))
                throw new Error('Invalid list');
              return response.data;
            }),
          ),
          detail: eventId
            ? this.api.detail(org, eventId).pipe(
                defaultIfEmpty(undefined),
                map(response => {
                  if (!validTraceDetail(response?.data, org, eventId))
                    throw new Error('Invalid detail');
                  return response.data.event;
                }),
              )
            : of(null),
        }).subscribe({
          next: response => {
            if (!this.active(epoch, actor)) return;
            this.context.set(context);
            this.page.set(response.list);
            this.selected.set(response.detail);
            this.loading.set(false);
          },
          error: error => {
            if (this.active(epoch, actor)) this.failure(error);
          },
        }),
      );
    } catch (error) {
      if (this.active(epoch, actor)) this.failure(error);
    }
  }
  private failure(error: unknown): void {
    this.clear();
    if (
      (error instanceof HttpErrorResponse &&
        [401, 403, 404].includes(error.status)) ||
      (error instanceof AuthFailure &&
        ['unauthorized', 'not-found'].includes(error.kind))
    )
      this.denied.set(true);
    else this.error.set('Không thể tải hoạt động. Vui lòng thử lại.');
  }
  toggleMenu(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#trace-menu')
      ?.showModal();
  }
  closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#trace-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="trace-menu"]')
      ?.focus();
  }
}

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
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {combineLatest, forkJoin, Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {
  MapAgents,
  MapOverview,
  OrganizationMapService,
  mapStatuses,
  mapUuid,
  validMapAgents,
  validMapOverview,
} from './organization-map.service';

@Component({
  selector: 'app-organization-map',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './organization-map.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './organization-map.component.css',
  ],
})
export class OrganizationMapComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(OrganizationMapService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  private invalidRoute = false;
  id = '';
  teamId: string | null = null;
  teamPage = 0;
  agentPage = 0;
  query = '';
  status = '';
  agentQuery = '';
  search = '';
  selectedStatus = '';
  agentSearch = '';
  readonly statuses = mapStatuses;
  readonly context = signal<Workspace | null>(null);
  readonly overview = signal<MapOverview | null>(null);
  readonly inspector = signal<MapAgents | null>(null);
  readonly loading = signal(false);
  readonly denied = signal(false);
  readonly error = signal('');
  readonly menuOpen = signal(false);
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
    combineLatest([this.route.paramMap, this.route.queryParamMap])
      .pipe(takeUntilDestroyed())
      .subscribe(([params, q]) => {
        this.clear();
        this.id = params.get('id') || '';
        this.teamId = q.get('teamId');
        this.invalidRoute = q.keys.some(
          k =>
            ![
              'teamId',
              'teamPage',
              'query',
              'status',
              'agentPage',
              'agentQuery',
            ].includes(k) || q.getAll(k).length !== 1,
        );
        const page = (key: string) => {
          const raw = q.get(key) ?? '0';
          return /^\d+$/.test(raw) ? Number(raw) : -1;
        };
        this.teamPage = page('teamPage');
        this.agentPage = page('agentPage');
        this.query = q.get('query') ?? '';
        this.status = q.get('status') ?? '';
        this.agentQuery = q.get('agentQuery') ?? '';
        this.search = this.query;
        this.selectedStatus = this.status;
        this.agentSearch = this.agentQuery;
        void this.load();
      });
  }
  private clear(): void {
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.overview.set(null);
    this.inspector.set(null);
    this.context.set(null);
    this.loading.set(false);
    this.error.set('');
    this.search = '';
    this.agentSearch = '';
    this.selectedStatus = '';
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
  async load(): Promise<void> {
    this.clear();
    this.denied.set(false);
    if (
      this.invalidRoute ||
      !mapUuid(this.id) ||
      (this.teamId !== null && !mapUuid(this.teamId)) ||
      [this.teamPage, this.agentPage].some(
        p => !Number.isSafeInteger(p) || p < 0 || p > 100000,
      ) ||
      (this.status && !this.statuses.some(s => s === this.status))
    ) {
      this.error.set('Bộ lọc không hợp lệ. Xóa bộ lọc để tiếp tục.');
      return;
    }
    this.search = this.query;
    this.agentSearch = this.agentQuery;
    this.selectedStatus = this.status;
    const epoch = this.epoch;
    const actor = this.auth.user()?.id;
    this.loading.set(true);
    try {
      const session = await this.auth.session();
      if (!this.active(epoch, actor)) return;
      if (session.id !== actor) {
        this.clear();
        this.denied.set(true);
        return;
      }
      const context = await this.auth.getWorkspace(this.id);
      if (!this.active(epoch, actor)) return;
      if (
        context.id !== this.id ||
        !['ADMIN', 'MEMBER'].includes(context.role)
      ) {
        this.clear();
        this.denied.set(true);
        return;
      }
      this.requests.add(
        forkJoin({
          overview: this.api.overview(
            this.id,
            this.teamPage,
            this.query,
            this.status,
          ),
          agents: this.api.agents(
            this.id,
            this.teamId,
            this.agentPage,
            this.agentQuery,
          ),
        }).subscribe({
          next: response => {
            if (!this.active(epoch, actor)) return;
            if (
              !validMapOverview(
                response.overview?.data,
                this.id,
                this.teamPage,
                !!(this.query || this.status),
              ) ||
              !validMapAgents(
                response.agents?.data,
                this.id,
                this.teamId,
                this.agentPage,
              )
            ) {
              this.clear();
              this.error.set('Dữ liệu không hợp lệ. Vui lòng thử lại.');
              return;
            }
            this.context.set(context);
            this.overview.set(response.overview.data);
            this.inspector.set(response.agents.data);
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
    else this.error.set('Không thể tải Organization map. Vui lòng thử lại.');
  }
  select(teamId: string | null): void {
    this.navigate({teamId, agentPage: 0, agentQuery: ''});
  }
  applyTeams(): void {
    this.navigate({
      teamPage: 0,
      query: this.search,
      status: this.selectedStatus,
      agentPage: 0,
      agentQuery: '',
    });
  }
  applyAgents(): void {
    this.navigate({agentPage: 0, agentQuery: this.agentSearch});
  }
  changePage(kind: 'teamPage' | 'agentPage', page: number): void {
    if (Number.isInteger(page) && page >= 0 && page <= 100000)
      this.navigate({[kind]: page});
  }
  clearFilters(): void {
    this.navigate({
      teamId: null,
      teamPage: 0,
      query: '',
      status: '',
      agentPage: 0,
      agentQuery: '',
    });
  }
  private navigate(changes: Record<string, string | number | null>): void {
    const values = {
      teamId: this.teamId,
      teamPage: this.teamPage,
      query: this.query,
      status: this.status,
      agentPage: this.agentPage,
      agentQuery: this.agentQuery,
      ...changes,
    };
    const queryParams = Object.fromEntries(
      Object.entries(values).filter(
        ([, v]) => v !== null && v !== '' && v !== 0,
      ),
    );
    this.clear();
    const target = this.router.createUrlTree(
      ['/workspace', this.id, 'organization-map'],
      {queryParams},
    );
    if (this.router.serializeUrl(target) === this.router.url) void this.load();
    else void this.router.navigateByUrl(target);
  }
  toggleMenu(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#org-map-menu')
      ?.showModal();
  }
  closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#org-map-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="org-map-menu"]')
      ?.focus();
  }
}

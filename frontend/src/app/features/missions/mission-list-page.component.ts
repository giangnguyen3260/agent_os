import {HttpErrorResponse} from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormsModule} from '@angular/forms';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {combineLatest, Subscription} from 'rxjs';
import {
  AuthFailure,
  AuthService,
  Workspace,
} from '../../core/auth/auth.service';
import {uuid} from '../teams/team.service';
import {
  MissionDirectoryService,
  missionStatuses,
  DirectoryPage,
  validDirectory,
} from './mission-directory.service';

import {
  TeamDirectoryService,
  DirectoryPage as TeamPage,
  DirectoryItem as TeamItem,
  validDirectory as validTeams,
} from '../teams/team-directory.service';

@Component({
  selector: 'app-mission-list-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './mission-list-page.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './mission-list-page.component.css',
  ],
})
export class MissionListPageComponent {
  private readonly teams = inject(TeamDirectoryService);
  readonly teamResult = signal<TeamPage | null>(null);
  readonly teamLoading = signal(false);
  readonly teamError = signal('');
  readonly chooserOpen = signal(false);
  chooserMode: 'filter' | 'create' = 'filter';
  teamSearch = '';
  teamQuery = '';
  teamPage = 0;
  selectedTeamId = '';
  selectedTeamName = '';
  private chooserEpoch = 0;
  private chooserRequest = new Subscription();
  private opener: HTMLElement | null = null;
  readonly auth = inject(AuthService);
  private readonly api = inject(MissionDirectoryService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroy = inject(DestroyRef);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  private requests = new Subscription();
  private epoch = 0;
  private account = this.auth.user()?.id;
  id = '';
  page = 0;
  query = '';
  status = '';
  teamId = '';
  search = '';
  selectedStatus = '';
  readonly statuses = missionStatuses;
  readonly context = signal<Workspace | null>(null);
  readonly result = signal<DirectoryPage | null>(null);
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
      .subscribe(([params, query]) => {
        this.id = params.get('id') || '';
        const raw = query.get('page') || '0';
        this.page = /^\d+$/.test(raw) ? Number(raw) : -1;
        this.query = query.get('query') || '';
        this.status = query.get('status') || '';
        this.teamId = query.get('teamId') || '';
        this.selectedTeamId = this.teamId;
        this.selectedTeamName = '';
        this.search = this.query;
        this.selectedStatus = this.status;
        void this.load();
      });
  }
  private clear(): void {
    this.closeChooser();
    this.epoch++;
    this.requests.unsubscribe();
    this.requests = new Subscription();
    this.selectedTeamId = '';
    this.selectedTeamName = '';
    this.result.set(null);
    this.context.set(null);
    this.loading.set(false);
    this.error.set('');
    this.closeMenu();
  }
  async load(): Promise<void> {
    this.clear();
    this.denied.set(false);
    if (
      !uuid(this.id) ||
      !Number.isInteger(this.page) ||
      this.page < 0 ||
      this.page > 100000 ||
      this.query.length > 200 ||
      (this.status && !this.statuses.some(status => status === this.status)) ||
      (this.teamId && !uuid(this.teamId))
    ) {
      this.error.set('Invalid directory filters. Clear filters to continue.');
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
      const context = await this.auth.getWorkspace(this.id);
      if (!active()) return;
      if (
        !account ||
        context.id !== this.id ||
        !['ADMIN', 'MEMBER'].includes(context.role)
      ) {
        this.clear();
        this.denied.set(true);
        return;
      }
      this.context.set(context);
      this.requests.add(
        this.api
          .list(this.id, this.page, this.query, this.status, this.teamId)
          .subscribe({
            next: response => {
              if (!active()) return;
              this.loading.set(false);
              if (
                !validDirectory(
                  response?.data,
                  this.id,
                  this.page,
                  this.teamId,
                  this.status,
                )
              ) {
                this.error.set(
                  'Invalid directory response. Try again to load current data.',
                );
                return;
              }
              this.result.set(response.data);
              this.selectedTeamId = response.data.team?.id || '';
              this.selectedTeamName = response.data.team?.name || '';
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
    this.selectedTeamId = '';
    this.selectedTeamName = '';
    this.result.set(null);
    this.context.set(null);
    this.loading.set(false);
    if (
      (error instanceof HttpErrorResponse &&
        [401, 403, 404].includes(error.status)) ||
      (error instanceof AuthFailure &&
        ['unauthorized', 'not-found'].includes(error.kind))
    ) {
      this.denied.set(true);
    } else this.error.set('Unable to load missions. Try again.');
  }
  apply(): void {
    this.navigate(
      0,
      this.search.trim(),
      this.selectedStatus,
      this.selectedTeamId,
    );
  }
  clearFilters(): void {
    this.navigate(0, '', '', '');
  }
  changePage(page: number): void {
    this.navigate(page, this.query, this.status, this.teamId);
  }
  private navigate(
    page: number,
    query: string,
    status: string,
    teamId: string,
  ): void {
    const target = this.router.createUrlTree(
      ['/workspace', this.id, 'missions'],
      {
        queryParams: {
          ...(page ? {page} : {}),
          ...(query ? {query} : {}),
          ...(status ? {status} : {}),
          ...(teamId ? {teamId} : {}),
        },
      },
    );
    if (this.router.serializeUrl(target) === this.router.url) void this.load();
    else void this.router.navigateByUrl(target);
  }
  openChooser(mode: 'filter' | 'create', event: Event): void {
    if (
      !this.context() ||
      (mode === 'create' && this.context()?.role !== 'ADMIN')
    )
      return;
    this.opener = event.currentTarget as HTMLElement;
    this.chooserMode = mode;
    this.teamSearch = '';
    this.teamQuery = '';
    this.teamPage = 0;
    this.chooserOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#team-chooser')
      ?.showModal();
    void this.loadTeams(0);
  }
  closeChooser(): void {
    this.chooserEpoch++;
    this.chooserRequest.unsubscribe();
    this.chooserRequest = new Subscription();
    this.teamResult.set(null);
    this.teamLoading.set(false);
    this.teamError.set('');
    if (!this.chooserOpen()) return;
    this.chooserOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#team-chooser')
      ?.close();
    this.opener?.focus();
  }
  searchTeams(): void {
    this.teamQuery = this.teamSearch.trim();
    void this.loadTeams(0);
  }
  async loadTeams(page: number): Promise<void> {
    this.chooserRequest.unsubscribe();
    this.chooserRequest = new Subscription();
    const epoch = ++this.chooserEpoch;
    const account = this.auth.user()?.id;
    const active = () =>
      epoch === this.chooserEpoch &&
      this.chooserOpen() &&
      !this.destroy.destroyed &&
      account === this.auth.user()?.id;
    this.teamPage = page;
    this.teamResult.set(null);
    this.teamError.set('');
    this.teamLoading.set(true);
    try {
      const context = await this.auth.getWorkspace(this.id);
      if (!active()) return;
      if (
        !account ||
        context.id !== this.id ||
        !['ADMIN', 'MEMBER'].includes(context.role) ||
        (this.chooserMode === 'create' && context.role !== 'ADMIN')
      ) {
        this.clear();
        this.denied.set(true);
        return;
      }
      this.context.set(context);
      this.chooserRequest.add(
        this.teams.list(this.id, page, this.teamQuery, '').subscribe({
          next: response => {
            if (!active()) return;
            this.teamLoading.set(false);
            if (!validTeams(response?.data, this.id, page)) {
              this.teamError.set('Invalid Team response. Try again.');
              return;
            }
            this.teamResult.set(response.data);
          },
          error: error => {
            if (!active()) return;
            this.teamLoading.set(false);
            if (
              error instanceof HttpErrorResponse &&
              [401, 403, 404].includes(error.status)
            ) {
              this.clear();
              this.denied.set(true);
            } else this.teamError.set('Unable to load teams. Try again.');
          },
        }),
      );
    } catch (error) {
      if (active()) {
        this.closeChooser();
        this.failure(error);
      }
    }
  }
  chooseTeam(team: TeamItem): void {
    if (this.chooserMode === 'create') {
      if (team.status === 'ARCHIVED' || this.context()?.role !== 'ADMIN')
        return;
      this.closeChooser();
      void this.router.navigate([
        '/workspace',
        this.id,
        'teams',
        team.id,
        'missions',
        'new',
      ]);
    } else {
      this.selectedTeamId = team.id;
      this.selectedTeamName = team.name;
      this.closeChooser();
    }
  }
  toggleMenu(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      return;
    }
    this.menuOpen.set(true);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#directory-menu')
      ?.showModal();
  }
  closeMenu(): void {
    if (!this.menuOpen()) return;
    this.menuOpen.set(false);
    this.element.nativeElement
      .querySelector<HTMLDialogElement>('#directory-menu')
      ?.close();
    this.element.nativeElement
      .querySelector<HTMLElement>('[aria-controls="directory-menu"]')
      ?.focus();
  }
}

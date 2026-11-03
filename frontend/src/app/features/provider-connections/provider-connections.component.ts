import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {NgTemplateOutlet} from '@angular/common';
import {Component, inject, signal} from '@angular/core';
import {takeUntilDestroyed, toObservable} from '@angular/core/rxjs-interop';
import {ActivatedRoute, Router, RouterLink} from '@angular/router';
import {
  catchError,
  combineLatest,
  distinctUntilChanged,
  map,
  of,
  startWith,
  Subject,
  switchMap,
} from 'rxjs';
import {AuthService} from '../../core/auth/auth.service';
import {uuid} from '../task-dependencies/task-dependencies-validation';
import {parseProviderPage, ProviderPage} from './provider-list-wire';

@Component({
  selector: 'app-provider-connections',
  standalone: true,
  imports: [RouterLink, NgTemplateOutlet],
  templateUrl: './provider-connections.component.html',
  styleUrls: [
    '../dashboard/dashboard-page.component.css',
    './provider-connections.component.css',
  ],
})
export class ProviderConnectionsComponent {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly refresh = new Subject<void>();
  readonly organization = signal('');
  readonly data = signal<ProviderPage | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly expanded = signal<string | null>(null);
  readonly providers = {
    OPENAI: 'OpenAI',
    ANTHROPIC: 'Anthropic / Claude',
    GOOGLE_GEMINI: 'Google Gemini',
  };

  constructor() {
    combineLatest([
      this.route.paramMap,
      this.route.queryParamMap,
      toObservable(this.auth.user).pipe(
        map(user => user?.id),
        distinctUntilChanged(),
      ),
      this.refresh.pipe(startWith(undefined)),
    ])
      .pipe(
        switchMap(([params, query, actor]) => {
          this.data.set(null);
          this.expanded.set(null);
          this.error.set('');
          const org = params.get('id');
          this.organization.set(org ?? '');
          const raw = query.get('page') ?? '0';
          if (!actor) {
            this.loading.set(false);
            void this.router.navigate(['/login']);
            return of(null);
          }
          if (
            !uuid(org) ||
            query.keys.some(key => key !== 'page') ||
            query.getAll('page').length > 1 ||
            !/^(0|[1-9][0-9]{0,5})$/.test(raw) ||
            Number(raw) > 100000
          ) {
            this.loading.set(false);
            this.error.set('Trang danh sách không hợp lệ.');
            return of(null);
          }
          const page = Number(raw);
          this.loading.set(true);
          return this.http
            .get<unknown>(
              `/api/v1/organizations/${org}/provider-connections?page=${page}`,
              {headers: {Accept: 'application/json'}},
            )
            .pipe(
              map(value => {
                if (this.auth.user()?.id !== actor) return null;
                return parseProviderPage(value, org, page);
              }),
              catchError(error => {
                if (this.auth.user()?.id !== actor) return of(null);
                const status =
                  error instanceof HttpErrorResponse ? error.status : 0;
                if (status === 401) void this.router.navigate(['/login']);
                this.error.set(
                  status === 403
                    ? 'Bạn cần quyền ADMIN để xem danh sách kết nối.'
                    : status === 429
                      ? 'Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
                      : 'Không thể tải danh sách kết nối. Vui lòng thử lại.',
                );
                return of(null);
              }),
            );
        }),
        takeUntilDestroyed(),
      )
      .subscribe(page => {
        this.loading.set(false);
        this.data.set(page);
      });
  }

  reload() {
    this.refresh.next();
  }

  toggle(id: string) {
    this.expanded.update(current => (current === id ? null : id));
  }
}

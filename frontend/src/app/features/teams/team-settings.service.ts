import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {firstValueFrom, timeout} from 'rxjs';
import {SettingsBody} from './team-settings-validation';

@Injectable({providedIn: 'root'})
export class TeamSettingsService {
  private readonly http = inject(HttpClient);
  private base(org: string, team: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/teams/${encodeURIComponent(team)}/settings`;
  }
  private async get(
    url: string,
    params?: Record<string, string | number>,
  ): Promise<unknown> {
    const response = await firstValueFrom(
      this.http.get<{data: unknown}>(url, {params}).pipe(timeout(30000)),
    );
    return response?.data;
  }
  context(org: string, team: string) {
    return this.get(this.base(org, team));
  }
  candidates(org: string, team: string, page: number, query: string) {
    return this.get(`${this.base(org, team)}/candidates`, {page, query});
  }
  receipt(org: string, team: string, key: string) {
    return this.get(
      `${this.base(org, team)}/receipts/${encodeURIComponent(key)}`,
    );
  }
  async csrf(): Promise<HttpHeaders> {
    const response = await firstValueFrom(
      this.http
        .get<{data: {token: string; headerName: string}}>('/api/v1/auth/csrf')
        .pipe(timeout(30000)),
    );
    if (
      !response?.data?.token ||
      !/^[-A-Za-z0-9]+$/.test(response.data.headerName)
    )
      throw Error('Unavailable CSRF');
    return new HttpHeaders().set(response.data.headerName, response.data.token);
  }
  async save(
    org: string,
    team: string,
    body: SettingsBody,
    key: string,
    headers: HttpHeaders,
  ): Promise<unknown> {
    const response = await firstValueFrom(
      this.http
        .post<{
          data: unknown;
        }>(this.base(org, team), body, {
          headers: headers.set('Idempotency-Key', key),
        })
        .pipe(timeout(30000)),
    );
    return response?.data;
  }
}

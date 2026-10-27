import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {firstValueFrom, timeout, Subject, takeUntil} from 'rxjs';
import {Body, Scope} from './task-edit-validation';
@Injectable({providedIn: 'root'})
export class TaskEditService {
  private readonly http = inject(HttpClient);
  private base(s: Scope) {
    return `/api/v1/organizations/${encodeURIComponent(s.organizationId)}/teams/${encodeURIComponent(s.teamId)}/missions/${encodeURIComponent(s.missionId)}/tasks/${encodeURIComponent(s.taskId)}/draft-settings`;
  }
  async get(url: string, cancel: Subject<void>): Promise<unknown> {
    return (
      await firstValueFrom(
        this.http
          .get<{data: unknown}>(url)
          .pipe(timeout(30000), takeUntil(cancel)),
      )
    )?.data;
  }
  context(s: Scope, cancel: Subject<void>) {
    return this.get(this.base(s), cancel);
  }
  workspace(org: string, cancel: Subject<void>) {
    return this.get(
      `/api/v1/auth/workspaces/${encodeURIComponent(org)}`,
      cancel,
    );
  }
  receipt(s: Scope, key: string, cancel: Subject<void>) {
    return this.get(
      `${this.base(s)}/receipts/${encodeURIComponent(key)}`,
      cancel,
    );
  }
  async csrf(cancel: Subject<void>) {
    const d = (await this.get('/api/v1/auth/csrf', cancel)) as {
      token?: unknown;
      headerName?: unknown;
    };
    if (
      typeof d?.token !== 'string' ||
      !d.token ||
      typeof d.headerName !== 'string' ||
      !/^[-A-Za-z0-9]+$/.test(d.headerName)
    )
      throw Error('CSRF unavailable');
    return new HttpHeaders().set(d.headerName, d.token);
  }
  async save(
    s: Scope,
    body: Body,
    key: string,
    headers: HttpHeaders,
    cancel: Subject<void>,
  ): Promise<unknown> {
    const r = await firstValueFrom(
      this.http
        .post<{
          data: unknown;
        }>(this.base(s), body, {
          headers: headers.set('Idempotency-Key', key),
          observe: 'response',
        })
        .pipe(timeout(30000), takeUntil(cancel)),
    );
    if (r.status !== 200 && r.status !== 201) throw Error('Unknown status');
    return r.body?.data;
  }
}

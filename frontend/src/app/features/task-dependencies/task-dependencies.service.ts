import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {firstValueFrom, timeout, Subject, takeUntil} from 'rxjs';
import {Body, Scope, decodeData} from './task-dependencies-validation';
@Injectable({providedIn: 'root'})
export class TaskDependenciesService {
  private readonly http = inject(HttpClient);
  private base(s: Scope) {
    return `/api/v1/organizations/${encodeURIComponent(s.organizationId)}/teams/${encodeURIComponent(s.teamId)}/missions/${encodeURIComponent(s.missionId)}/tasks/${encodeURIComponent(s.taskId)}/draft-dependencies`;
  }
  async get(url: string, cancel: Subject<void>): Promise<unknown> {
    const bytes = await firstValueFrom(
      this.http
        .get(url, {responseType: 'arraybuffer'})
        .pipe(timeout(30000), takeUntil(cancel)),
    );
    return decodeData(bytes);
  }

  session(cancel: Subject<void>) {
    return this.get('/api/v1/auth/session', cancel);
  }
  context(s: Scope, cancel: Subject<void>) {
    return this.get(this.base(s), cancel);
  }
  candidates(s: Scope, page: number, query: string, cancel: Subject<void>) {
    return this.get(
      `${this.base(s)}/eligible-dependencies?page=${page}&query=${encodeURIComponent(query)}`,
      cancel,
    );
  }
  candidate(s: Scope, id: string, cancel: Subject<void>) {
    return this.get(
      `${this.base(s)}/eligible-dependencies/${encodeURIComponent(id)}`,
      cancel,
    );
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
        .post(this.base(s), body, {
          responseType: 'arraybuffer',
          headers: headers.set('Idempotency-Key', key),
          observe: 'response',
        })
        .pipe(timeout(30000), takeUntil(cancel)),
    );
    if (r.status !== 200 && r.status !== 201) throw Error('Unknown status');
    if (!r.body) throw Error('Unknown receipt');
    return decodeData(r.body);
  }
}

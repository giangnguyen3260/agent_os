import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {switchMap, timeout} from 'rxjs';
import {AgentDetail} from './agent.service';

export type StudioText =
  | 'responsibilities'
  | 'expectedInput'
  | 'outputFormat'
  | 'acceptanceCriteria'
  | 'model'
  | 'skills'
  | 'tools'
  | 'limits';
export type StudioNumber = 'concurrency' | 'maxTokensPerRun' | 'monthlyBudget';
export type StudioBody = Record<StudioText, string | null> &
  Record<StudioNumber, number | null> & {expectedRevision: number};
export interface StudioConfiguration {
  agent: AgentDetail;
  revision: number;
  concurrency: number | null;
  maxTokensPerRun: number | null;
  monthlyBudget: number | null;
  currency: 'USD';
}
@Injectable({providedIn: 'root'})
export class AgentStudioService {
  private readonly http = inject(HttpClient);
  private url(org: string, agent: string): string {
    return `/api/v1/organizations/${encodeURIComponent(org)}/agents/${encodeURIComponent(agent)}/configuration`;
  }
  get(org: string, agent: string) {
    return this.http
      .get<{data: StudioConfiguration}>(this.url(org, agent))
      .pipe(timeout(30000));
  }
  save(org: string, agent: string, body: StudioBody, key: string) {
    return this.http
      .get<{data: {token: string; headerName: string}}>('/api/v1/auth/csrf')
      .pipe(
        switchMap(({data}) =>
          this.http.put<{data: StudioConfiguration}>(
            this.url(org, agent),
            body,
            {
              headers: new HttpHeaders()
                .set(data.headerName, data.token)
                .set('Idempotency-Key', key),
            },
          ),
        ),
        timeout(30000),
      );
  }
}

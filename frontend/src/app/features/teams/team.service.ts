import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {switchMap, timeout} from 'rxjs';

export interface Candidate {
  id: string;
  name: string;
  level: string;
  status: string;
}
export interface CandidatePage {
  items: Candidate[];
  page: number;
  size: number;
  total: number;
}
export interface TeamBody {
  name: string;
  purpose: string;
  description: string;
  managerAgentId: string | null;
  agentIds: string[];
  intake: 'MANUAL';
  concurrency: number;
  monthlyBudget: number;
}
export interface TeamDetail
  extends Omit<
    TeamBody,
    'concurrency' | 'monthlyBudget' | 'intake' | 'agentIds'
  > {
  concurrency: number | null;
  monthlyBudget: number | null;
  intake: 'MANUAL' | null;
  agentIds: string[] | null;
  id: string;
  organizationId: string;
  status: string;
  currency: string;
  createdAt: string;
}
export const uuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export function validTeam(
  value: TeamDetail | undefined,
  org: string,
): value is TeamDetail {
  return (
    !!value &&
    uuid(value.id) &&
    value.organizationId === org &&
    value.status === 'DRAFT'
  );
}
@Injectable({providedIn: 'root'})
export class TeamService {
  private readonly http = inject(HttpClient);
  private base(org: string): string {
    return `/api/v1/organizations/${encodeURIComponent(org)}/teams`;
  }
  candidates(org: string, page: number, query: string) {
    return this.http
      .get<{
        data: CandidatePage;
      }>(`${this.base(org)}/eligible-agents`, {params: {page, query}})
      .pipe(timeout(30000));
  }
  detail(org: string, id: string) {
    return this.http
      .get<{data: TeamDetail}>(`${this.base(org)}/${encodeURIComponent(id)}`)
      .pipe(timeout(30000));
  }
  create(org: string, body: TeamBody, key: string) {
    return this.http
      .get<{data: {token: string; headerName: string}}>('/api/v1/auth/csrf')
      .pipe(
        switchMap(({data}) =>
          this.http.post<{data: TeamDetail}>(this.base(org), body, {
            headers: new HttpHeaders()
              .set(data.headerName, data.token)
              .set('Idempotency-Key', key),
          }),
        ),
        timeout(30000),
      );
  }
}

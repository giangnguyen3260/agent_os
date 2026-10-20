import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {switchMap, timeout} from 'rxjs';
import {uuid} from '../teams/team.service';

export interface AgentBody {
  name: string;
  role: string;
  teamId: string | null;
  description: string | null;
  responsibilities: string;
  expectedInput: string;
  outputFormat: string;
  acceptanceCriteria: string;
  model: string | null;
  skills: string | null;
  tools: string | null;
  limits: string | null;
}
export interface AgentDetail extends AgentBody {
  id: string;
  organizationId: string;
  level: string;
  status: string;
  autonomyLevel: string;
  createdAt: string;
}
export interface EligibleTeam {
  id: string;
  name: string;
  status: string;
}
export interface EligibleTeamPage {
  items: EligibleTeam[];
  page: number;
  size: number;
  total: number;
}
export function validAgent(
  value: AgentDetail | undefined,
  org: string,
): value is AgentDetail {
  return (
    !!value &&
    uuid(value.id) &&
    value.organizationId === org &&
    value.status === 'DRAFT'
  );
}
@Injectable({providedIn: 'root'})
export class AgentService {
  private readonly http = inject(HttpClient);
  private base(org: string): string {
    return `/api/v1/organizations/${encodeURIComponent(org)}/agents`;
  }
  candidates(org: string, page: number, query: string) {
    return this.http
      .get<{
        data: EligibleTeamPage;
      }>(`${this.base(org)}/eligible-teams`, {params: {page, size: 20, query}})
      .pipe(timeout(30000));
  }
  detail(org: string, id: string) {
    return this.http
      .get<{data: AgentDetail}>(`${this.base(org)}/${encodeURIComponent(id)}`)
      .pipe(timeout(30000));
  }
  create(org: string, body: AgentBody, key: string) {
    return this.http
      .get<{data: {token: string; headerName: string}}>('/api/v1/auth/csrf')
      .pipe(
        switchMap(({data}) =>
          this.http.post<{data: AgentDetail}>(this.base(org), body, {
            headers: new HttpHeaders()
              .set(data.headerName, data.token)
              .set('Idempotency-Key', key),
          }),
        ),
        timeout(30000),
      );
  }
}

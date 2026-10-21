import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {switchMap, timeout} from 'rxjs';
import {uuid} from '../teams/team.service';
export interface MissionBody {
  title: string;
  goal: string;
  requiredOutput: string;
  managerAgentId: string | null;
  priority: string;
  deadline: string | null;
  budgetLimit: number | null;
  approvalMode: 'HUMAN_REQUIRED';
  context: string | null;
}
export interface MissionDetail
  extends Omit<MissionBody, 'priority' | 'approvalMode' | 'requiredOutput'> {
  id: string;
  organizationId: string;
  teamId: string;
  teamName: string;
  managerName: string | null;
  status: string;
  currency: string | null;
  createdAt: string;
  priority: string | null;
  approvalMode: string | null;
  requiredOutput: string | null;
}
export function validDate(value: string): boolean {
  return (
    /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    Number.isFinite(Date.parse(value)) &&
    new Date(value).toISOString().slice(0, 10) === value
  );
}
export function validMission(
  v: MissionDetail | undefined,
  org: string,
  team: string,
  strict = false,
): v is MissionDetail {
  if (!v) return false;
  const text = (s: unknown, max: number) =>
    typeof s === 'string' && s.trim().length > 0 && s.length <= max;
  const nullableText = (s: unknown, max: number) =>
    s === null || (typeof s === 'string' && s.length <= max);
  return (
    uuid(v.id) &&
    v.organizationId === org &&
    v.teamId === team &&
    text(v.teamName, 255) &&
    text(v.title, 255) &&
    text(v.goal, 4000) &&
    (v.requiredOutput === null ? !strict : text(v.requiredOutput, 4000)) &&
    (v.managerAgentId === null || uuid(v.managerAgentId)) &&
    nullableText(v.managerName, 255) &&
    (strict
      ? v.status === 'CREATED'
      : [
          'CREATED',
          'PLANNING',
          'READY',
          'RUNNING',
          'WAITING_APPROVAL',
          'REVIEW',
          'COMPLETED',
          'FAILED',
          'CANCELLED',
        ].includes(v.status)) &&
    (v.priority === null
      ? !strict
      : ['LOW', 'NORMAL', 'HIGH', 'URGENT'].includes(v.priority)) &&
    (v.deadline === null ||
      (typeof v.deadline === 'string' && validDate(v.deadline))) &&
    (v.budgetLimit === null ||
      (typeof v.budgetLimit === 'number' &&
        Number.isFinite(v.budgetLimit) &&
        v.budgetLimit >= 0 &&
        v.budgetLimit <= 1000000 &&
        Math.abs(v.budgetLimit * 100 - Math.round(v.budgetLimit * 100)) <
          0.000001)) &&
    (v.currency === 'USD' || (!strict && v.currency === null)) &&
    (v.approvalMode === 'HUMAN_REQUIRED' ||
      (!strict && v.approvalMode === null)) &&
    nullableText(v.context, 8000) &&
    typeof v.createdAt === 'string' &&
    /^\d{4}-\d{2}-\d{2}T/.test(v.createdAt) &&
    Number.isFinite(Date.parse(v.createdAt))
  );
}
@Injectable({providedIn: 'root'})
export class MissionService {
  private readonly http = inject(HttpClient);
  private base(org: string, team: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/teams/${encodeURIComponent(team)}/missions`;
  }
  managers(org: string, team: string, page: number, query: string) {
    return this.http
      .get<{
        data: import('../teams/team.service').CandidatePage;
      }>(`${this.base(org, team)}/eligible-managers`, {params: {page, query}})
      .pipe(timeout(30000));
  }
  detail(org: string, team: string, id: string) {
    return this.http
      .get<{
        data: MissionDetail;
      }>(`${this.base(org, team)}/${encodeURIComponent(id)}`)
      .pipe(timeout(30000));
  }
  create(org: string, team: string, body: MissionBody, key: string) {
    return this.http
      .get<{data: {token: string; headerName: string}}>('/api/v1/auth/csrf')
      .pipe(
        switchMap(({data}) =>
          this.http.post<{data: MissionDetail}>(this.base(org, team), body, {
            headers: new HttpHeaders()
              .set(data.headerName, data.token)
              .set('Idempotency-Key', key),
          }),
        ),
        timeout(30000),
      );
  }
}

import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {map, switchMap, timeout} from 'rxjs';
import {TaskDetail} from './task-detail.service';
import {uuid} from '../teams/team.service';
import {missionStatuses} from './mission-directory.service';
import {taskStatuses} from './mission-workspace.service';
export interface CreationContext {
  organizationId: string;
  teamId: string;
  teamName: string;
  missionId: string;
  missionTitle: string;
  missionStatus: string;
  intakeVersion: number;
  intakePaused: boolean;
  canCreate: boolean;
}
export interface TaskBody {
  title: string;
  description: string;
  acceptanceCriteria: string;
  priority: string;
  ownerAgentId: string | null;
  dependencyIds: string[];
  expectedIntakeVersion: number;
}
export interface Candidate {
  id: string;
  name?: string;
  title?: string;
  level?: string;
  status: string;
}
export interface Candidates {
  items: Candidate[];
  page: number;
  size: number;
  total: number;
}
export const exact = (v: unknown, keys: string[]): boolean =>
  typeof v === 'object' &&
  v !== null &&
  Object.keys(v).length === keys.length &&
  Object.keys(v).every(k => keys.includes(k));
const text = (v: unknown) => typeof v === 'string' && !!v.trim();
export function validContext(
  v: CreationContext,
  org: string,
  team: string,
  mission: string,
) {
  return (
    !!v &&
    exact(v, [
      'organizationId',
      'teamId',
      'teamName',
      'missionId',
      'missionTitle',
      'missionStatus',
      'intakeVersion',
      'intakePaused',
      'canCreate',
    ]) &&
    v.organizationId === org &&
    v.teamId === team &&
    v.missionId === mission &&
    text(v.teamName) &&
    text(v.missionTitle) &&
    missionStatuses.some(s => s === v.missionStatus) &&
    Number.isSafeInteger(v.intakeVersion) &&
    v.intakeVersion >= 0 &&
    typeof v.intakePaused === 'boolean' &&
    typeof v.canCreate === 'boolean' &&
    (!v.canCreate ||
      (!v.intakePaused &&
        ['CREATED', 'READY', 'RUNNING', 'WAITING_APPROVAL'].includes(
          v.missionStatus,
        )))
  );
}
export function validCandidates(v: Candidates, page: number, owners: boolean) {
  return (
    !!v &&
    exact(v, ['items', 'page', 'size', 'total']) &&
    v.page === page &&
    v.size === 20 &&
    Number.isSafeInteger(v.total) &&
    v.total >= 0 &&
    Array.isArray(v.items) &&
    v.items.length <= 20 &&
    v.items.length === Math.min(20, Math.max(0, v.total - page * 20)) &&
    new Set(v.items.map(i => i?.id)).size === v.items.length &&
    v.items.every(
      i =>
        !!i &&
        uuid(i.id) &&
        (owners
          ? exact(i, ['id', 'name', 'level', 'status']) &&
            text(i.name) &&
            typeof i.level === 'string' &&
            ['DRAFT', 'ACTIVE'].includes(i.status)
          : exact(i, ['id', 'title', 'status']) &&
            text(i.title) &&
            taskStatuses.slice(2).some(s => s === i.status)),
    )
  );
}
@Injectable({providedIn: 'root'})
export class NewTaskCreateService {
  private readonly http = inject(HttpClient);
  base(org: string, team: string, mission: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/teams/${encodeURIComponent(team)}/missions/${encodeURIComponent(mission)}/tasks`;
  }
  context(org: string, team: string, mission: string) {
    return this.http
      .get<{
        data: CreationContext;
      }>(`${this.base(org, team, mission)}/creation-context`)
      .pipe(
        map(r => r.data),
        timeout(30000),
      );
  }
  candidates(
    org: string,
    team: string,
    mission: string,
    owners: boolean,
    page: number,
    query: string,
  ) {
    return this.http
      .get<{
        data: Candidates;
      }>(
        `${this.base(org, team, mission)}/eligible-${owners ? 'owners' : 'dependencies'}`,
        {params: {page, query}},
      )
      .pipe(
        map(r => r.data),
        timeout(30000),
      );
  }
  create(
    org: string,
    team: string,
    mission: string,
    body: TaskBody,
    key: string,
  ) {
    return this.http
      .get<{data: {token: string; headerName: string}}>('/api/v1/auth/csrf')
      .pipe(
        switchMap(({data}) =>
          this.http.post<{data: TaskDetail}>(
            this.base(org, team, mission),
            body,
            {
              headers: new HttpHeaders()
                .set(data.headerName, data.token)
                .set('Idempotency-Key', key),
              observe: 'response',
            },
          ),
        ),
        timeout(30000),
      );
  }
}

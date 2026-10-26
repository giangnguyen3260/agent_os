import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';

export const mapStatuses = ['DRAFT', 'ACTIVE', 'ARCHIVED'] as const;
export const mapUuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
    v,
  );
export interface MapTeam {
  id: string;
  name: string;
  purpose: string | null;
  status: string;
  managerAgentId: string | null;
  managerName: string | null;
  managerUnavailable: boolean;
  agentCount: number;
  intake: string | null;
}
export interface MapAgent {
  id: string;
  name: string;
  role: string | null;
  level: string | null;
  status: string | null;
  reportsToAgentId: string | null;
  reportsToName: string | null;
  reportingUnavailable: boolean;
}
export interface MapPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}
export interface MapOverview {
  organizationId: string;
  name: string;
  teamTotal: number;
  agentTotal: number;
  unassignedTotal: number;
  teams: MapPage<MapTeam>;
}
export interface MapAgents extends MapPage<MapAgent> {
  organizationId: string;
  teamId: string | null;
  team: MapTeam | null;
}
const record = (v: unknown): v is Record<string, unknown> =>
  !!v && typeof v === 'object' && !Array.isArray(v);
const exact = (v: unknown, keys: string): v is Record<string, unknown> =>
  record(v) &&
  Object.keys(v).sort().join(',') === keys.split(',').sort().join(',');
const count = (v: unknown): v is number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
const nullable = (v: unknown): boolean => v === null || typeof v === 'string';
const identity = (id: unknown, name: unknown, unavailable: unknown): boolean =>
  typeof unavailable === 'boolean' &&
  ((id === null && name === null) ||
    (mapUuid(id) && typeof name === 'string' && !unavailable));
export function validMapTeam(v: unknown): v is MapTeam {
  return (
    exact(
      v,
      'id,name,purpose,status,managerAgentId,managerName,managerUnavailable,agentCount,intake',
    ) &&
    mapUuid(v['id']) &&
    typeof v['name'] === 'string' &&
    nullable(v['purpose']) &&
    typeof v['status'] === 'string' &&
    nullable(v['intake']) &&
    count(v['agentCount']) &&
    identity(v['managerAgentId'], v['managerName'], v['managerUnavailable'])
  );
}
export function validMapAgent(v: unknown): v is MapAgent {
  return (
    exact(
      v,
      'id,name,role,level,status,reportsToAgentId,reportsToName,reportingUnavailable',
    ) &&
    mapUuid(v['id']) &&
    typeof v['name'] === 'string' &&
    ['role', 'level', 'status'].every(k => nullable(v[k])) &&
    identity(
      v['reportsToAgentId'],
      v['reportsToName'],
      v['reportingUnavailable'],
    ) &&
    v['reportsToAgentId'] !== v['id']
  );
}
function validPage(
  v: Record<string, unknown>,
  page: number,
  valid: (v: unknown) => boolean,
): boolean {
  return (
    count(page) &&
    page <= 100000 &&
    v['page'] === page &&
    v['size'] === 20 &&
    count(v['total']) &&
    Array.isArray(v['items']) &&
    v['items'].length === Math.min(20, Math.max(0, v['total'] - page * 20)) &&
    v['items'].every(valid) &&
    new Set(v['items'].map(item => item.id)).size === v['items'].length
  );
}
export function validMapOverview(
  v: unknown,
  org: string,
  page: number,
  filtered = false,
): v is MapOverview {
  return (
    exact(
      v,
      'organizationId,name,teamTotal,agentTotal,unassignedTotal,teams',
    ) &&
    v['organizationId'] === org &&
    mapUuid(org) &&
    typeof v['name'] === 'string' &&
    count(v['teamTotal']) &&
    count(v['agentTotal']) &&
    count(v['unassignedTotal']) &&
    v['unassignedTotal'] <= v['agentTotal'] &&
    exact(v['teams'], 'items,page,size,total') &&
    validPage(v['teams'], page, validMapTeam) &&
    (v['teams']['total'] as number) <= v['teamTotal'] &&
    (filtered || v['teams']['total'] === v['teamTotal'])
  );
}
export function validMapAgents(
  v: unknown,
  org: string,
  teamId: string | null,
  page: number,
): v is MapAgents {
  return (
    exact(v, 'organizationId,teamId,team,items,page,size,total') &&
    mapUuid(org) &&
    v['organizationId'] === org &&
    v['teamId'] === teamId &&
    (teamId === null
      ? v['team'] === null
      : mapUuid(teamId) &&
        validMapTeam(v['team']) &&
        v['team'].id === teamId) &&
    validPage(v, page, validMapAgent)
  );
}
@Injectable({providedIn: 'root'})
export class OrganizationMapService {
  private readonly http = inject(HttpClient);
  overview(org: string, page: number, query: string, status: string) {
    return this.http
      .get<{
        data: unknown;
      }>(`/api/v1/organizations/${encodeURIComponent(org)}/organization-map`, {
        params: {page, query, ...(status ? {status} : {})},
      })
      .pipe(timeout(30000));
  }
  agents(org: string, teamId: string | null, page: number, query: string) {
    return this.http
      .get<{
        data: unknown;
      }>(
        `/api/v1/organizations/${encodeURIComponent(org)}/organization-map/agents`,
        {params: {page, query, ...(teamId ? {teamId} : {})}},
      )
      .pipe(timeout(30000));
  }
}

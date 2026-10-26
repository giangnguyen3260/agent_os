import {HttpClient, HttpParams} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {firstValueFrom, timeout} from 'rxjs';
export {
  permissionsUuid as traceUuid,
  validPermissionsWorkspace as validTraceWorkspace,
} from '../agents/agent-permissions.service';
import {permissionsUuid as uuid} from '../agents/agent-permissions.service';
export interface TraceEvent {
  id: string;
  eventType: string;
  actorType: string;
  createdAt: string;
  team: {id: string; name: string} | null;
  mission: {id: string; title: string};
  task: {id: string; title: string} | null;
}
export interface TracePage {
  organizationId: string;
  generatedAt: string;
  runtimeStatus: 'UNAVAILABLE';
  items: TraceEvent[];
  page: number;
  size: number;
  total: number;
}
export interface TraceDetail {
  organizationId: string;
  generatedAt: string;
  runtimeStatus: 'UNAVAILABLE';
  event: TraceEvent;
}
export interface TraceFilters {
  q: string;
  missionId: string;
  taskId: string;
  page: number;
}
const exact = (v: unknown, keys: string): v is Record<string, unknown> =>
  !!v &&
  typeof v === 'object' &&
  !Array.isArray(v) &&
  Object.keys(v).sort().join(',') === keys.split(',').sort().join(',');
export const traceInstant = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(v) &&
  Number.isFinite(Date.parse(v)) &&
  new Date(v).toISOString().slice(0, 19) === v.slice(0, 19);
const reference = (v: unknown, label: string): boolean =>
  exact(v, `id,${label}`) && uuid(v['id']) && typeof v[label] === 'string';
export function validTraceEvent(v: unknown): v is TraceEvent {
  return (
    exact(v, 'id,eventType,actorType,createdAt,team,mission,task') &&
    uuid(v['id']) &&
    typeof v['eventType'] === 'string' &&
    typeof v['actorType'] === 'string' &&
    traceInstant(v['createdAt']) &&
    reference(v['mission'], 'title') &&
    (v['team'] === null || reference(v['team'], 'name')) &&
    (v['task'] === null ||
      (v['team'] !== null && reference(v['task'], 'title')))
  );
}
const envelope = (v: Record<string, unknown>, org: string) =>
  uuid(org) &&
  v['organizationId'] === org &&
  traceInstant(v['generatedAt']) &&
  v['runtimeStatus'] === 'UNAVAILABLE';
// Preserve nanoseconds when checking the server's stable order.
const instantKey = (value: string) =>
  value.slice(0, 19) +
  '.' +
  value.slice(19, -1).replace('.', '').padEnd(9, '0');
export function validTracePage(
  v: unknown,
  org: string,
  filters: TraceFilters,
): v is TracePage {
  if (
    !exact(
      v,
      'organizationId,generatedAt,runtimeStatus,items,page,size,total',
    ) ||
    !envelope(v, org) ||
    v['page'] !== filters.page ||
    !Number.isInteger(v['page']) ||
    filters.page < 0 ||
    filters.page > 100000 ||
    v['size'] !== 20 ||
    !Number.isSafeInteger(v['total']) ||
    Number(v['total']) < 0 ||
    !Array.isArray(v['items'])
  )
    return false;
  const items = v['items'];
  return (
    items.length ===
      Math.min(20, Math.max(0, Number(v['total']) - filters.page * 20)) &&
    items.every(validTraceEvent) &&
    new Set(items.map(e => e.id)).size === items.length &&
    items.every(
      (e, i) =>
        (!filters.missionId || e.mission.id === filters.missionId) &&
        (!filters.taskId || e.task?.id === filters.taskId) &&
        (i === 0 ||
          instantKey(items[i - 1].createdAt) > instantKey(e.createdAt) ||
          (instantKey(items[i - 1].createdAt) === instantKey(e.createdAt) &&
            items[i - 1].id < e.id)),
    )
  );
}
export function validTraceDetail(
  v: unknown,
  org: string,
  eventId: string,
): v is TraceDetail {
  return (
    exact(v, 'organizationId,generatedAt,runtimeStatus,event') &&
    envelope(v, org) &&
    uuid(eventId) &&
    validTraceEvent(v['event']) &&
    v['event'].id === eventId
  );
}
// Java String.strip whitespace; deliberately excludes NBSP and BOM.
export const stripTraceQuery = (q: string): string =>
  q.replace(
    // Java whitespace intentionally includes these control characters.
    // eslint-disable-next-line no-control-regex
    /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g,
    '',
  );
@Injectable({providedIn: 'root'})
export class TraceService {
  private readonly http = inject(HttpClient);
  workspace(org: string): Promise<{data: unknown}> {
    return firstValueFrom(
      this.http
        .get<{
          data: unknown;
        }>(`/api/v1/auth/workspaces/${encodeURIComponent(org)}`)
        .pipe(timeout(30000)),
    );
  }
  list(org: string, filters: TraceFilters) {
    let params = new HttpParams().set('page', filters.page);
    for (const key of ['q', 'missionId', 'taskId'] as const)
      if (filters[key]) params = params.set(key, filters[key]);
    return this.http
      .get<{
        data: unknown;
      }>(`/api/v1/organizations/${encodeURIComponent(org)}/trace-events`, {
        params,
      })
      .pipe(timeout(30000));
  }
  detail(org: string, eventId: string) {
    return this.http
      .get<{
        data: unknown;
      }>(
        `/api/v1/organizations/${encodeURIComponent(org)}/trace-events/${encodeURIComponent(eventId)}`,
      )
      .pipe(timeout(30000));
  }
}

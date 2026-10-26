import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {firstValueFrom, timeout} from 'rxjs';
import {Workspace} from '../../core/auth/auth.service';
export const permissionsUuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
    v,
  );
export interface PermissionRequest {
  kind: 'MODEL' | 'TOOLS' | 'SKILLS';
  configured: string | null;
  effective: null;
  grantedBy: null;
  scope: null;
}
export interface AgentPermissions {
  organizationId: string;
  agentId: string;
  name: string;
  status: string;
  configurationRevision: number;
  checkedAt: string;
  teamState: 'ASSIGNED' | 'UNASSIGNED' | 'UNAVAILABLE';
  team: {id: string; name: string; status: string} | null;
  configurationPresent: boolean;
  sourceStatus: 'UNAVAILABLE';
  requests: PermissionRequest[];
}
const exact = (v: unknown, keys: string): v is Record<string, unknown> =>
  !!v &&
  typeof v === 'object' &&
  !Array.isArray(v) &&
  Object.keys(v).sort().join(',') === keys.split(',').sort().join(',');
export function validPermissionsWorkspace(
  v: unknown,
  org: string,
): v is Workspace {
  return (
    exact(v, 'id,name,role') &&
    permissionsUuid(org) &&
    v['id'] === org &&
    typeof v['name'] === 'string' &&
    (v['role'] === 'ADMIN' || v['role'] === 'MEMBER')
  );
}
export function validAgentPermissions(
  v: unknown,
  org: string,
  agent: string,
): v is AgentPermissions {
  return (
    exact(
      v,
      'organizationId,agentId,name,status,configurationRevision,checkedAt,teamState,team,configurationPresent,sourceStatus,requests',
    ) &&
    permissionsUuid(org) &&
    permissionsUuid(agent) &&
    v['organizationId'] === org &&
    v['agentId'] === agent &&
    typeof v['name'] === 'string' &&
    typeof v['status'] === 'string' &&
    Number.isInteger(v['configurationRevision']) &&
    Number(v['configurationRevision']) >= 0 &&
    Number(v['configurationRevision']) <= 2147483647 &&
    typeof v['checkedAt'] === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(
      v['checkedAt'],
    ) &&
    Number.isFinite(Date.parse(v['checkedAt'])) &&
    new Date(v['checkedAt']).toISOString().slice(0, 19) ===
      v['checkedAt'].slice(0, 19) &&
    (v['teamState'] === 'ASSIGNED'
      ? exact(v['team'], 'id,name,status') &&
        permissionsUuid(v['team']['id']) &&
        typeof v['team']['name'] === 'string' &&
        typeof v['team']['status'] === 'string'
      : (v['teamState'] === 'UNASSIGNED' || v['teamState'] === 'UNAVAILABLE') &&
        v['team'] === null) &&
    typeof v['configurationPresent'] === 'boolean' &&
    v['sourceStatus'] === 'UNAVAILABLE' &&
    Array.isArray(v['requests']) &&
    v['requests'].length === 3 &&
    v['requests'].every(
      (r, i) =>
        exact(r, 'kind,configured,effective,grantedBy,scope') &&
        r['kind'] === ['MODEL', 'TOOLS', 'SKILLS'][i] &&
        (r['configured'] === null ||
          (v['configurationPresent'] && typeof r['configured'] === 'string')) &&
        r['effective'] === null &&
        r['grantedBy'] === null &&
        r['scope'] === null,
    )
  );
}
@Injectable({providedIn: 'root'})
export class AgentPermissionsService {
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
  read(org: string, agent: string) {
    const endpoint = `/api/v1/organizations/${encodeURIComponent(org)}/agents/${encodeURIComponent(agent)}/permissions`;
    return this.http.get<{data: unknown}>(endpoint).pipe(timeout(30000));
  }
}

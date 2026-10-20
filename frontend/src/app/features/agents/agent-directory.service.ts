import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';
import {uuid} from '../teams/team.service';

export const agentStatuses = [
  'DRAFT',
  'ACTIVE',
  'DEGRADED',
  'SUSPENDED',
  'ARCHIVED',
] as const;
export interface DirectoryItem {
  id: string;
  organizationId: string;
  name: string;
  role: string;
  status: string;
  teamId: string | null;
  teamName: string | null;
  configurationRevision: number;
  concurrencyLimit: number | null;
}
export interface DirectoryPage {
  items: DirectoryItem[];
  page: number;
  size: number;
  total: number;
}
export function validDirectory(
  value: DirectoryPage | undefined,
  org: string,
  page: number,
): value is DirectoryPage {
  return (
    !!value &&
    value.page === page &&
    value.size === 20 &&
    Number.isSafeInteger(value.total) &&
    value.total >= 0 &&
    Array.isArray(value.items) &&
    value.items.length <= 20 &&
    value.items.length === Math.min(20, Math.max(0, value.total - page * 20)) &&
    new Set(value.items.map(item => item?.id)).size === value.items.length &&
    value.items.every(
      item =>
        !!item &&
        uuid(item.id) &&
        item.organizationId === org &&
        typeof item.name === 'string' &&
        !!item.name.trim() &&
        typeof item.role === 'string' &&
        agentStatuses.some(status => status === item.status) &&
        (item.teamId === null || uuid(item.teamId)) &&
        (item.teamName === null || typeof item.teamName === 'string') &&
        Number.isSafeInteger(item.configurationRevision) &&
        item.configurationRevision >= 0 &&
        (item.concurrencyLimit === null ||
          (Number.isInteger(item.concurrencyLimit) &&
            item.concurrencyLimit >= 1 &&
            item.concurrencyLimit <= 1000)),
    )
  );
}
@Injectable({providedIn: 'root'})
export class AgentDirectoryService {
  private readonly http = inject(HttpClient);
  list(
    org: string,
    page: number,
    query: string,
    status: string,
    teamId: string,
  ) {
    return this.http
      .get<{data: DirectoryPage}>(
        `/api/v1/organizations/${encodeURIComponent(org)}/agents/directory`,
        {
          params: {
            page,
            query,
            ...(status ? {status} : {}),
            ...(teamId ? {teamId} : {}),
          },
        },
      )
      .pipe(timeout(30000));
  }
}

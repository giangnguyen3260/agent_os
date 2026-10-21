import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';
import {uuid} from './team.service';

export const teamStatuses = ['DRAFT', 'ACTIVE', 'ARCHIVED'] as const;
export interface DirectoryItem {
  id: string;
  organizationId: string;
  name: string;
  purpose: string | null;
  status: string;
  managerAgentId: string | null;
  managerName: string | null;
  agentCount: number;
  totalTasks: number;
  runningTasks: number;
  pendingTasks: number;
  reviewTasks: number;
}
export interface DirectoryPage {
  items: DirectoryItem[];
  page: number;
  size: number;
  total: number;
}
const count = (value: number): boolean =>
  Number.isSafeInteger(value) && value >= 0;
export function validDirectory(
  value: DirectoryPage | undefined,
  org: string,
  page: number,
): value is DirectoryPage {
  return (
    !!value &&
    value.page === page &&
    value.size === 20 &&
    count(value.total) &&
    Array.isArray(value.items) &&
    value.items.length === Math.min(20, Math.max(0, value.total - page * 20)) &&
    new Set(value.items.map(item => item?.id)).size === value.items.length &&
    value.items.every(
      item =>
        !!item &&
        uuid(item.id) &&
        item.organizationId === org &&
        typeof item.name === 'string' &&
        !!item.name.trim() &&
        (item.purpose === null || typeof item.purpose === 'string') &&
        teamStatuses.some(status => status === item.status) &&
        (item.managerAgentId === null || uuid(item.managerAgentId)) &&
        (item.managerName === null || typeof item.managerName === 'string') &&
        [
          item.agentCount,
          item.totalTasks,
          item.runningTasks,
          item.pendingTasks,
          item.reviewTasks,
        ].every(count) &&
        item.runningTasks + item.pendingTasks + item.reviewTasks <=
          item.totalTasks,
    )
  );
}
@Injectable({providedIn: 'root'})
export class TeamDirectoryService {
  private readonly http = inject(HttpClient);
  list(org: string, page: number, query: string, status: string) {
    return this.http
      .get<{data: DirectoryPage}>(
        `/api/v1/organizations/${encodeURIComponent(org)}/teams/directory`,
        {
          params: {
            page,
            query,
            ...(status ? {status} : {}),
          },
        },
      )
      .pipe(timeout(30000));
  }
}

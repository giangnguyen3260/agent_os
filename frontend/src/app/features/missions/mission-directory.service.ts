import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';
import {uuid} from '../teams/team.service';

export const missionStatuses = [
  'CREATED',
  'PLANNING',
  'READY',
  'RUNNING',
  'WAITING_APPROVAL',
  'REVIEW',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
] as const;
export interface DirectoryItem {
  id: string;
  organizationId: string;
  title: string;
  status: string;
  priority: string | null;
  teamId: string | null;
  teamName: string | null;
  managerAgentId: string | null;
  managerName: string | null;
  totalTasks: number;
  completedTasks: number;
  pendingTasks: number;
  createdAt: string;
}
export interface DirectoryPage {
  items: DirectoryItem[];
  page: number;
  size: number;
  total: number;
  team: {id: string; name: string; status: string} | null;
}
export function validDirectory(
  value: DirectoryPage | undefined,
  org: string,
  page: number,
  teamId: string,
  status: string,
): value is DirectoryPage {
  const count = (v: number) => Number.isSafeInteger(v) && v >= 0;
  const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim();
  return (
    !!value &&
    value.page === page &&
    value.size === 20 &&
    count(value.total) &&
    (teamId
      ? !!value.team &&
        value.team.id === teamId &&
        text(value.team.name) &&
        ['DRAFT', 'ACTIVE', 'ARCHIVED'].includes(value.team.status)
      : value.team === null) &&
    Array.isArray(value.items) &&
    value.items.length === Math.min(20, Math.max(0, value.total - page * 20)) &&
    new Set(value.items.map(i => i?.id)).size === value.items.length &&
    value.items.every(
      i =>
        !!i &&
        uuid(i.id) &&
        i.organizationId === org &&
        text(i.title) &&
        missionStatuses.some(s => s === i.status) &&
        (!status || i.status === status) &&
        (i.priority === null ||
          ['LOW', 'NORMAL', 'HIGH', 'URGENT'].includes(i.priority)) &&
        (i.teamId === null
          ? i.teamName === null &&
            i.totalTasks === 0 &&
            i.completedTasks === 0 &&
            i.pendingTasks === 0
          : uuid(i.teamId) && text(i.teamName)) &&
        (!teamId || (i.teamId === teamId && i.teamName === value.team?.name)) &&
        (i.managerAgentId === null
          ? i.managerName === null
          : i.teamId !== null &&
            uuid(i.managerAgentId) &&
            text(i.managerName)) &&
        [i.totalTasks, i.completedTasks, i.pendingTasks].every(count) &&
        i.completedTasks + i.pendingTasks <= i.totalTasks &&
        typeof i.createdAt === 'string' &&
        /^\d{4}-\d{2}-\d{2}T/.test(i.createdAt) &&
        Number.isFinite(Date.parse(i.createdAt)),
    )
  );
}
@Injectable({providedIn: 'root'})
export class MissionDirectoryService {
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
        `/api/v1/organizations/${encodeURIComponent(org)}/missions/directory`,
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

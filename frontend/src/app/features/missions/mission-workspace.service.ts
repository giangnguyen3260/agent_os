import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';
import {Workspace} from '../../core/auth/auth.service';
import {validDate} from './mission.service';
import {uuid} from '../teams/team.service';
import {
  Task,
  Page,
  Activity,
  validPage,
  validActivity as validTeamActivity,
} from '../teams/team-workspace.service';
import {missionStatuses} from './mission-directory.service';
export type {Task, Page, Activity};
export {validPage};
export function validActivity(a: Activity): boolean {
  return typeof a === 'object' && a !== null && validTeamActivity(a);
}
export const taskStatuses = [
  'ALL',
  'PENDING',
  'DRAFT',
  'READY',
  'RUNNING',
  'WAITING',
  'BLOCKED',
  'IN_REVIEW',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
] as const;
export type TaskStatus = (typeof taskStatuses)[number];
export interface MissionSnapshot {
  generatedAt: string;
  mission: {
    id: string;
    organizationId: string;
    teamId: string;
    teamName: string;
    title: string;
    status: string;
    priority: string | null;
    managerAgentId: string | null;
    managerName: string | null;
  };
  summary: {
    totalTasks: number;
    completedTasks: number;
    runningTasks: number;
    pendingTasks: number;
    reviewTasks: number;
  };
}
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim();
const date = (v: unknown) =>
  typeof v === 'string' &&
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(
    v,
  ) &&
  validDate(v.slice(0, 10)) &&
  Number.isFinite(Date.parse(v));
const count = (v: unknown): v is number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
export function validSnapshot(
  v: MissionSnapshot | undefined,
  org: string,
  team: string,
  mission: string,
): v is MissionSnapshot {
  if (!v || !date(v.generatedAt) || !v.mission || !v.summary) return false;
  const m = v.mission,
    s = v.summary;
  return (
    m.id === mission &&
    m.organizationId === org &&
    m.teamId === team &&
    text(m.title) &&
    text(m.teamName) &&
    missionStatuses.some(status => status === m.status) &&
    (m.priority === null ||
      ['LOW', 'NORMAL', 'HIGH', 'URGENT'].includes(m.priority)) &&
    (m.managerAgentId === null
      ? m.managerName === null
      : uuid(m.managerAgentId) && text(m.managerName)) &&
    [
      s.totalTasks,
      s.completedTasks,
      s.runningTasks,
      s.pendingTasks,
      s.reviewTasks,
    ].every(count) &&
    s.completedTasks + s.runningTasks + s.pendingTasks + s.reviewTasks <=
      s.totalTasks
  );
}
export function validTask(
  t: Task,
  org: string,
  team: string,
  mission: string,
  status: TaskStatus,
): boolean {
  return (
    !!t &&
    uuid(t.id) &&
    t.organizationId === org &&
    t.teamId === team &&
    t.missionId === mission &&
    text(t.title) &&
    taskStatuses.slice(2).some(s => s === t.status) &&
    (status === 'ALL' ||
      (status === 'PENDING'
        ? ['WAITING', 'BLOCKED'].includes(t.status)
        : t.status === status)) &&
    (['WAITING', 'BLOCKED'].includes(t.status)
      ? text(t.pendingReason) && date(t.waitingSince) && text(t.resolver)
      : t.pendingReason === null &&
        t.waitingSince === null &&
        t.resolver === null)
  );
}
@Injectable({providedIn: 'root'})
export class MissionWorkspaceService {
  private readonly http = inject(HttpClient);
  authorize(org: string) {
    return this.http
      .get<{
        data: Workspace;
      }>(`/api/v1/auth/workspaces/${encodeURIComponent(org)}`)
      .pipe(timeout(30000));
  }
  private base(org: string, team: string, mission: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/teams/${encodeURIComponent(team)}/missions/${encodeURIComponent(mission)}/workspace`;
  }
  summary(org: string, team: string, mission: string) {
    return this.http
      .get<{data: MissionSnapshot}>(this.base(org, team, mission))
      .pipe(timeout(30000));
  }
  tasks(
    org: string,
    team: string,
    mission: string,
    status: TaskStatus,
    page: number,
  ) {
    return this.http
      .get<{
        data: Page<Task>;
      }>(`${this.base(org, team, mission)}/tasks`, {params: {status, page}})
      .pipe(timeout(30000));
  }
  activity(org: string, team: string, mission: string, page: number) {
    return this.http
      .get<{
        data: Page<Activity>;
      }>(`${this.base(org, team, mission)}/activity`, {params: {page}})
      .pipe(timeout(30000));
  }
}

import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';
import {
  MissionWorkspaceService,
  Task,
  validTask,
} from './mission-workspace.service';
import {Activity, Page} from '../teams/team-workspace.service';
import {validDate} from './mission.service';
export interface TaskDetail {
  generatedAt: string;
  task: Task & {createdAt: string; updatedAt: string};
  mission: {id: string; title: string};
  team: {id: string; name: string};
}
const date = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(
    v,
  ) &&
  validDate(v.slice(0, 10)) &&
  Number.isFinite(Date.parse(v));
const keys = (v: unknown, expected: string[]) =>
  typeof v === 'object' &&
  v !== null &&
  Object.keys(v).length === expected.length &&
  Object.keys(v).every(k => expected.includes(k));
export function validDetail(
  v: TaskDetail | undefined,
  org: string,
  team: string,
  mission: string,
  task: string,
): v is TaskDetail {
  return (
    !!v &&
    keys(v, ['generatedAt', 'task', 'mission', 'team']) &&
    keys(v.task, [
      'id',
      'organizationId',
      'teamId',
      'missionId',
      'title',
      'status',
      'pendingReason',
      'waitingSince',
      'resolver',
      'createdAt',
      'updatedAt',
    ]) &&
    keys(v.mission, ['id', 'title']) &&
    keys(v.team, ['id', 'name']) &&
    date(v.generatedAt) &&
    !!v.task &&
    v.task.id === task &&
    validTask(v.task, org, team, mission, 'ALL') &&
    date(v.task.createdAt) &&
    date(v.task.updatedAt) &&
    Date.parse(v.task.updatedAt) >= Date.parse(v.task.createdAt) &&
    v.mission?.id === mission &&
    typeof v.mission.title === 'string' &&
    !!v.mission.title.trim() &&
    v.team?.id === team &&
    typeof v.team.name === 'string' &&
    !!v.team.name.trim()
  );
}
@Injectable({providedIn: 'root'})
export class TaskDetailService {
  private readonly http = inject(HttpClient);
  private readonly workspace = inject(MissionWorkspaceService);
  authorize(org: string) {
    return this.workspace.authorize(org);
  }
  private base(org: string, team: string, mission: string, task: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/teams/${encodeURIComponent(team)}/missions/${encodeURIComponent(mission)}/tasks/${encodeURIComponent(task)}`;
  }
  get(org: string, team: string, mission: string, task: string) {
    return this.http
      .get<{data: TaskDetail}>(this.base(org, team, mission, task))
      .pipe(timeout(30000));
  }
  activity(
    org: string,
    team: string,
    mission: string,
    task: string,
    page: number,
  ) {
    return this.http
      .get<{
        data: Page<Activity>;
      }>(`${this.base(org, team, mission, task)}/activity`, {params: {page}})
      .pipe(timeout(30000));
  }
}

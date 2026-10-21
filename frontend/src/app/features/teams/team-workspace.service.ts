import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';
import {uuid} from './team.service';

export const lanes = [
  'INTAKE',
  'READY',
  'RUNNING',
  'REVIEW',
  'PENDING',
  'DONE',
  'FAILED',
  'CANCELLED',
] as const;
export type Lane = (typeof lanes)[number];
const statuses: Record<Lane, string[]> = {
  INTAKE: ['DRAFT'],
  READY: ['READY'],
  RUNNING: ['RUNNING'],
  REVIEW: ['IN_REVIEW'],
  PENDING: ['WAITING', 'BLOCKED'],
  DONE: ['COMPLETED'],
  FAILED: ['FAILED'],
  CANCELLED: ['CANCELLED'],
};
export interface Task {
  id: string;
  organizationId: string;
  teamId: string;
  missionId: string | null;
  title: string;
  status: string;
  pendingReason: string | null;
  waitingSince: string | null;
  resolver: string | null;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export interface Activity {
  id: string;
  eventType: string;
  actorType: string;
  createdAt: string;
}
export interface Snapshot {
  generatedAt: string;
  team: {
    id: string;
    organizationId: string;
    name: string;
    purpose: string | null;
    status: string;
    managerAgentId: string | null;
    managerName: string | null;
    intake: string | null;
    concurrency: number | null;
    monthlyBudget: number | null;
    currency: string | null;
  };
  summary: {
    agentCount: number;
    totalTasks: number;
    runningTasks: number;
    pendingTasks: number;
    reviewTasks: number;
  };
  lanes: {lane: Lane; total: number; items: Task[]}[];
}
const count = (v: unknown): v is number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim();
const nullable = (v: unknown) => v === null || typeof v === 'string';
const date = (v: unknown) =>
  typeof v === 'string' &&
  /^\d{4}-\d{2}-\d{2}T/.test(v) &&
  Number.isFinite(Date.parse(v));
export function validTask(
  t: Task,
  org: string,
  team: string,
  lane: Lane,
): boolean {
  return (
    !!t &&
    uuid(t.id) &&
    t.organizationId === org &&
    t.teamId === team &&
    (t.missionId === null || uuid(t.missionId)) &&
    text(t.title) &&
    statuses[lane].includes(t.status) &&
    (lane === 'PENDING'
      ? text(t.pendingReason) && date(t.waitingSince) && text(t.resolver)
      : t.pendingReason === null &&
        t.waitingSince === null &&
        t.resolver === null)
  );
}
export function validPage<T>(
  p: Page<T> | undefined,
  page: number,
  valid: (item: T) => boolean,
): p is Page<T> {
  return (
    !!p &&
    p.page === page &&
    p.size === 20 &&
    count(p.total) &&
    Array.isArray(p.items) &&
    p.items.length === Math.min(20, Math.max(0, p.total - page * 20)) &&
    p.items.every(valid) &&
    new Set(p.items.map(item => (item as {id: string})?.id)).size ===
      p.items.length
  );
}
export function validActivity(a: Activity): boolean {
  return (
    !!a &&
    Reflect.ownKeys(a).length === 4 &&
    ['id', 'eventType', 'actorType', 'createdAt'].every(key =>
      Object.prototype.hasOwnProperty.call(a, key),
    ) &&
    uuid(a.id) &&
    text(a.eventType) &&
    text(a.actorType) &&
    date(a.createdAt)
  );
}
export function validSnapshot(
  v: Snapshot | undefined,
  org: string,
  id: string,
): v is Snapshot {
  if (
    !v ||
    !date(v.generatedAt) ||
    !v.team ||
    !v.summary ||
    !Array.isArray(v.lanes)
  )
    return false;
  const t = v.team,
    s = v.summary;
  return (
    t.id === id &&
    t.organizationId === org &&
    text(t.name) &&
    nullable(t.purpose) &&
    ['DRAFT', 'ACTIVE', 'ARCHIVED'].includes(t.status) &&
    (t.managerAgentId === null || uuid(t.managerAgentId)) &&
    nullable(t.managerName) &&
    (t.intake === null || t.intake === 'MANUAL') &&
    (t.concurrency === null ||
      (count(t.concurrency) && t.concurrency >= 1 && t.concurrency <= 1000)) &&
    (t.monthlyBudget === null ||
      (typeof t.monthlyBudget === 'number' &&
        Number.isFinite(t.monthlyBudget) &&
        t.monthlyBudget >= 0 &&
        t.monthlyBudget <= 1000000)) &&
    (t.currency === null ||
      (typeof t.currency === 'string' && /^[A-Z]{3}$/.test(t.currency))) &&
    [
      s.agentCount,
      s.totalTasks,
      s.runningTasks,
      s.pendingTasks,
      s.reviewTasks,
    ].every(count) &&
    v.lanes.length === lanes.length &&
    v.lanes.every(
      (l, i) =>
        !!l &&
        l.lane === lanes[i] &&
        count(l.total) &&
        Array.isArray(l.items) &&
        l.items.length === Math.min(5, l.total) &&
        l.items.every(task => validTask(task, org, id, l.lane)),
    ) &&
    new Set(v.lanes.flatMap(l => l.items.map(t => t.id))).size ===
      v.lanes.reduce((n, l) => n + l.items.length, 0) &&
    v.lanes.reduce((n, l) => n + l.total, 0) === s.totalTasks &&
    v.lanes[2].total === s.runningTasks &&
    v.lanes[3].total === s.reviewTasks &&
    v.lanes[4].total === s.pendingTasks
  );
}
@Injectable({providedIn: 'root'})
export class TeamWorkspaceService {
  private readonly http = inject(HttpClient);
  private base(org: string, team: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/teams/${encodeURIComponent(team)}/workspace`;
  }
  summary(org: string, team: string) {
    return this.http
      .get<{data: Snapshot}>(this.base(org, team))
      .pipe(timeout(30000));
  }
  tasks(org: string, team: string, lane: Lane, page: number) {
    return this.http
      .get<{
        data: Page<Task>;
      }>(`${this.base(org, team)}/tasks`, {params: {lane, page}})
      .pipe(timeout(30000));
  }
  activity(org: string, team: string, page: number) {
    return this.http
      .get<{
        data: Page<Activity>;
      }>(`${this.base(org, team)}/activity`, {params: {page}})
      .pipe(timeout(30000));
  }
}

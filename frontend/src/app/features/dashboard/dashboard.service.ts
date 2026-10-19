import {HttpClient, HttpParams} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {map, Observable} from 'rxjs';

export type TaskState = 'RUNNING' | 'PENDING' | 'REVIEW';
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}
export interface DashboardTeam {
  id: string;
  name: string;
  status: string;
  agentCount: number;
  running: number;
  pending: number;
  review: number;
  health: 'ARCHIVED' | 'SETUP_REQUIRED' | 'ATTENTION' | 'NORMAL';
  healthReason: string;
}
export interface DashboardTask {
  id: string;
  title: string;
  status: string;
  teamId: string;
  teamName: string;
  missionId: string | null;
  pendingReason: string | null;
  waitingSince: string | null;
  resolver: string | null;
}
export interface Dashboard {
  generatedAt: string;
  snapshot: {
    running: number;
    pending: number;
    review: number;
    totalTeams: number;
    totalAgents: number;
    activeAgents: number;
  };
  capacity: {available: false; reason: string};
  performance: {available: false; reason: string};
  teams: Page<DashboardTeam>;
}
@Injectable({providedIn: 'root'})
export class DashboardService {
  private readonly http = inject(HttpClient);
  private url(id: string): string {
    return `/api/v1/organizations/${encodeURIComponent(id)}/dashboard`;
  }
  dashboard(id: string, teamPage: number): Observable<Dashboard> {
    return this.http
      .get<{data: Dashboard}>(this.url(id), {params: {teamPage}})
      .pipe(map(response => response.data));
  }
  tasks(
    id: string,
    state: TaskState,
    teamId: string,
    page: number,
  ): Observable<Page<DashboardTask>> {
    let params = new HttpParams().set('state', state).set('page', page);
    if (teamId) params = params.set('teamId', teamId);
    return this.http
      .get<{data: Page<DashboardTask>}>(`${this.url(id)}/tasks`, {params})
      .pipe(map(response => response.data));
  }
}

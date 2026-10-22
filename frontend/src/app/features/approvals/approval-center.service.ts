import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';
import {uuid} from '../teams/team.service';

export const groups = ['PENDING', 'NEEDS_INFORMATION', 'DECIDED'] as const;
export type Group = (typeof groups)[number];
export const closedStatuses = [
  'APPROVED',
  'REJECTED',
  'EXPIRED',
  'CANCELLED',
  'INVALIDATED',
];
export interface Approval {
  id: string;
  organizationId: string;
  teamId: string;
  teamName: string;
  missionId: string | null;
  missionTitle: string | null;
  taskId: string | null;
  taskTitle: string | null;
  requesterId: string | null;
  requesterName: string | null;
  title: string;
  actionType: string;
  riskLevel: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}
export interface ApprovalPage {
  generatedAt: string;
  items: Approval[];
  page: number;
  size: number;
  total: number;
  counts: {pending: number; needsInformation: number; decided: number};
}
const keys = (v: object, names: string) =>
  Object.keys(v).sort().join(',') === names.split(' ').sort().join(',');
const count = (v: unknown): v is number =>
  Number.isSafeInteger(v) && (v as number) >= 0;
const text = (v: unknown): v is string => typeof v === 'string' && !!v.trim();
const date = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^\d{4}-\d{2}-\d{2}T/.test(v) &&
  Number.isFinite(Date.parse(v));
export function validApprovals(
  v: ApprovalPage | undefined,
  org: string,
  page: number,
  group: Group,
): v is ApprovalPage {
  if (
    !v ||
    !keys(v, 'generatedAt items page size total counts') ||
    !date(v.generatedAt) ||
    v.page !== page ||
    v.size !== 20 ||
    !count(v.total) ||
    !v.counts ||
    !keys(v.counts, 'pending needsInformation decided') ||
    !Object.values(v.counts).every(count)
  )
    return false;
  const expected =
    group === 'PENDING'
      ? v.counts.pending
      : group === 'NEEDS_INFORMATION'
        ? v.counts.needsInformation
        : v.counts.decided;
  const states = group === 'DECIDED' ? closedStatuses : [group];
  const reference = (id: string | null, name: string | null) =>
    id === null ? name === null : uuid(id) && text(name);
  return (
    v.total === expected &&
    Array.isArray(v.items) &&
    v.items.length === Math.min(20, Math.max(0, v.total - page * 20)) &&
    new Set(v.items.map(i => i?.id)).size === v.items.length &&
    v.items.every(
      i =>
        !!i &&
        keys(
          i,
          'id organizationId teamId teamName missionId missionTitle taskId taskTitle requesterId requesterName title actionType riskLevel status createdAt updatedAt',
        ) &&
        uuid(i.id) &&
        i.organizationId === org &&
        uuid(i.teamId) &&
        text(i.teamName) &&
        reference(i.missionId, i.missionTitle) &&
        reference(i.taskId, i.taskTitle) &&
        (!i.taskId || !!i.missionId) &&
        reference(i.requesterId, i.requesterName) &&
        text(i.title) &&
        i.title.length <= 255 &&
        text(i.actionType) &&
        i.actionType.length <= 100 &&
        ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(i.riskLevel) &&
        states.includes(i.status) &&
        date(i.createdAt) &&
        date(i.updatedAt) &&
        Date.parse(i.updatedAt) >= Date.parse(i.createdAt),
    )
  );
}
@Injectable({providedIn: 'root'})
export class ApprovalCenterService {
  private readonly http = inject(HttpClient);
  list(org: string, page: number, group: Group) {
    return this.http
      .get<{
        data: ApprovalPage;
      }>(`/api/v1/organizations/${encodeURIComponent(org)}/approvals`, {
        params: {page, group},
      })
      .pipe(timeout(30000));
  }
}

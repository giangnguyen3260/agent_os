import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {switchMap, timeout} from 'rxjs';
import {
  Approval,
  Group,
  validApprovals,
  closedStatuses,
} from './approval-center.service';
import {uuid} from '../teams/team.service';
import {Workspace} from '../../core/auth/auth.service';

export interface Snapshot {
  actionId: string;
  version: number;
  payloadSha256: string;
  actionType: string;
  target: string;
  scope: string;
  effectSummary: string;
  estimatedCost: string | null;
  currency: string | null;
  expiresAt: string;
  parameters: {destination: string; resourcePath: string; visibility: string};
}
export interface Decision {
  id: string;
  approvalId: string;
  status: 'APPROVED' | 'REJECTED';
  actorId: string;
  actorName: string;
  reason: string | null;
  snapshotVersion: number;
  snapshotSha256: string;
  decidedAt: string;
}
export interface ApprovalDetail {
  generatedAt: string;
  request: Approval;
  snapshot: Snapshot | null;
  eligibility: {canApprove: boolean; canReject: boolean; blockers: string[]};
  decision: Decision | null;
  executionAvailable: false;
}
export interface DecisionBody {
  snapshotVersion: number;
  snapshotSha256: string;
  acknowledgement: boolean;
  reason: string;
}
export type Operation = 'approve' | 'reject';
const exact = (v: unknown, fields: string) =>
  !!v &&
  typeof v === 'object' &&
  Object.keys(v).sort().join(',') === fields.split(' ').sort().join(',');
const text = (v: unknown, max: number) =>
  typeof v === 'string' && !!v.trim() && v.length <= max;
const date = (v: unknown) =>
  typeof v === 'string' &&
  /^\d{4}-\d{2}-\d{2}T/.test(v) &&
  Number.isFinite(Date.parse(v));
const digest = (v: unknown) =>
  typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
const version = (v: unknown): v is number =>
  Number.isSafeInteger(v) && (v as number) > 0;
export function validDecision(
  d: Decision | undefined,
  approvalId: string,
): d is Decision {
  return (
    !!d &&
    exact(
      d,
      'id approvalId status actorId actorName reason snapshotVersion snapshotSha256 decidedAt',
    ) &&
    uuid(d.id) &&
    d.approvalId === approvalId &&
    ['APPROVED', 'REJECTED'].includes(d.status) &&
    uuid(d.actorId) &&
    text(d.actorName, 255) &&
    (d.reason === null ||
      (typeof d.reason === 'string' && d.reason.length <= 2000)) &&
    (d.status !== 'REJECTED' ||
      (typeof d.reason === 'string' && !!d.reason.trim())) &&
    version(d.snapshotVersion) &&
    digest(d.snapshotSha256) &&
    date(d.decidedAt)
  );
}
export function validDetail(
  d: ApprovalDetail | undefined,
  org: string,
  id: string,
): d is ApprovalDetail {
  if (
    !d ||
    !exact(
      d,
      'generatedAt request snapshot eligibility decision executionAvailable',
    ) ||
    !d.request ||
    d.request.id !== id ||
    d.executionAvailable !== false
  )
    return false;
  const group: Group =
    d.request.status === 'NEEDS_INFORMATION'
      ? 'NEEDS_INFORMATION'
      : closedStatuses.includes(d.request.status)
        ? 'DECIDED'
        : 'PENDING';
  if (
    !validApprovals(
      {
        generatedAt: d.generatedAt,
        items: [d.request],
        page: 0,
        size: 20,
        total: 1,
        counts: {
          pending: group === 'PENDING' ? 1 : 0,
          needsInformation: group === 'NEEDS_INFORMATION' ? 1 : 0,
          decided: group === 'DECIDED' ? 1 : 0,
        },
      },
      org,
      0,
      group,
    )
  )
    return false;
  const s = d.snapshot,
    e = d.eligibility;
  if (
    s !== null &&
    (!exact(
      s,
      'actionId version payloadSha256 actionType target scope effectSummary estimatedCost currency expiresAt parameters',
    ) ||
      !uuid(s.actionId) ||
      !version(s.version) ||
      !digest(s.payloadSha256) ||
      s.actionType !== d.request.actionType ||
      !text(s.target, 500) ||
      !text(s.scope, 200) ||
      !text(s.effectSummary, 2000) ||
      !date(s.expiresAt) ||
      !exact(s.parameters, 'destination resourcePath visibility') ||
      s.parameters.destination !== 'internal-review' ||
      s.parameters.visibility !== 'TEAM' ||
      typeof s.parameters.resourcePath !== 'string' ||
      !/^\/[a-zA-Z0-9/_-]{1,200}$/.test(s.parameters.resourcePath) ||
      (s.estimatedCost === null
        ? s.currency !== null
        : typeof s.estimatedCost !== 'string' ||
          !/^\d{1,9}(\.\d{1,4})?$/.test(s.estimatedCost) ||
          s.currency !== 'USD'))
  )
    return false;
  if (
    !exact(e, 'canApprove canReject blockers') ||
    typeof e.canApprove !== 'boolean' ||
    typeof e.canReject !== 'boolean' ||
    !Array.isArray(e.blockers) ||
    e.blockers.some(x => typeof x !== 'string' || !/^[A-Z_]{1,80}$/.test(x)) ||
    new Set(e.blockers).size !== e.blockers.length
  )
    return false;
  if (
    (e.canApprove || e.canReject) &&
    (!s ||
      d.request.status !== 'PENDING' ||
      e.blockers.length ||
      Date.parse(s.expiresAt) <= Date.parse(d.generatedAt))
  )
    return false;
  return (
    d.decision === null ||
    (validDecision(d.decision, id) &&
      d.decision.status === d.request.status &&
      (!s ||
        (d.decision.snapshotVersion === s.version &&
          d.decision.snapshotSha256 === s.payloadSha256)) &&
      !e.canApprove &&
      !e.canReject)
  );
}
@Injectable({providedIn: 'root'})
export class ApprovalDetailService {
  private readonly http = inject(HttpClient);
  private root(org: string, id: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/approvals/${encodeURIComponent(id)}`;
  }
  workspace(org: string) {
    return this.http
      .get<{
        data: Workspace;
      }>(`/api/v1/auth/workspaces/${encodeURIComponent(org)}`)
      .pipe(timeout(30000));
  }
  get(org: string, id: string) {
    return this.http
      .get<{data: ApprovalDetail}>(this.root(org, id))
      .pipe(timeout(30000));
  }
  decide(
    org: string,
    id: string,
    operation: Operation,
    key: string,
    body: DecisionBody,
  ) {
    return this.http
      .get<{data: {headerName: string; token: string}}>('/api/v1/auth/csrf')
      .pipe(
        timeout(30000),
        switchMap(csrf => {
          if (!csrf?.data?.headerName || !csrf.data.token)
            throw new Error('CSRF unavailable');
          return this.http
            .post<{data: Decision}>(
              `${this.root(org, id)}/${operation}`,
              body,
              {
                headers: new HttpHeaders()
                  .set(csrf.data.headerName, csrf.data.token)
                  .set('Idempotency-Key', key),
              },
            )
            .pipe(timeout(30000));
        }),
      );
  }
}

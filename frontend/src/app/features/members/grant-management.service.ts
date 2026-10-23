import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {switchMap, timeout} from 'rxjs';
import {uuid} from '../teams/team.service';
import {Grant, stripMemberQuery} from './member-access.service';

export type Operation = 'ISSUE' | 'REVOKE';
export const risks = ['LOW', 'MEDIUM', 'HIGH'] as const;
export interface Recipient {
  accountId: string;
  displayName: string;
  enabled: boolean;
  membershipPresent: boolean;
}
export interface Selection {
  teamId: string;
  teamName: string;
  teamStatus: string;
  actionType: 'PUBLISH_PREVIEW';
  riskLevel: string;
  unexpiredCount: number;
}
export interface IssueContext {
  organizationId: string;
  accountId: string;
  checkedAt: string;
  revision: number;
  recipient: Recipient;
  selection: Selection | null;
  canIssue: boolean;
  blockers: string[];
}
export interface RevokeContext {
  organizationId: string;
  accountId: string;
  checkedAt: string;
  revision: number;
  recipient: Recipient;
  grant: Grant;
  grantFingerprint: string;
  remainingScopeGrants: number;
  canRevoke: boolean;
  blockers: string[];
}
export interface IssueCommand {
  expectedRevision: number;
  teamId: string;
  actionType: 'PUBLISH_PREVIEW';
  riskLevel: string;
  durationHours: number;
  reason: string;
  acknowledgement: true;
}
export interface RevokeCommand {
  expectedRevision: number;
  grantFingerprint: string;
  reason: string;
  acknowledgement: true;
}
export type Command = IssueCommand | RevokeCommand;
export interface Result {
  operation: Operation;
  organizationId: string;
  accountId: string;
  actorId: string;
  actorName: string;
  idempotencyKey: string;
  grantId: string;
  eventId: string;
  revision: number;
  recordedAt: string;
  expiresAt: string;
  revokedAt: string | null;
}
export interface History {
  organizationId: string;
  accountId: string;
  grantId: string;
  checkedAt: string;
  items: {
    id: string;
    operation: Operation;
    actorId: string;
    actorName: string;
    reason: string;
    recordedAt: string;
    revision: number;
  }[];
  page: number;
  size: number;
  total: number;
}
const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
export const exact = (v: unknown, fields: string): boolean =>
  record(v) &&
  Object.keys(v).sort().join(',') === fields.split(' ').sort().join(',');
const text = (v: unknown, max = 255): v is string =>
  typeof v === 'string' && !!v.trim() && [...v].length <= max;
const integer = (v: unknown, max = 2147483647): v is number =>
  Number.isSafeInteger(v) && (v as number) >= 0 && (v as number) <= max;
export function stamp(v: unknown): bigint | null {
  if (typeof v !== 'string') return null;
  const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(v);
  if (!m) return null;
  const seconds = Date.parse(m[1] + 'Z');
  if (
    !Number.isFinite(seconds) ||
    new Date(seconds).toISOString().slice(0, 19) !== m[1]
  )
    return null;
  return BigInt(seconds) * 1000000n + BigInt((m[2] || '').padEnd(9, '0'));
}
export const reasonText = stripMemberQuery;
export function validReason(v: string): boolean {
  const s = reasonText(v);
  return (
    !!s &&
    [...s].length <= 1000 &&
    [...s].every(c => {
      const n = c.codePointAt(0)!;
      return n !== 127 && (n >= 32 || [9, 10, 13].includes(n));
    })
  );
}
function recipient(v: unknown, account: string): v is Recipient {
  return (
    record(v) &&
    exact(v, 'accountId displayName enabled membershipPresent') &&
    v['accountId'] === account &&
    text(v['displayName']) &&
    typeof v['enabled'] === 'boolean' &&
    typeof v['membershipPresent'] === 'boolean'
  );
}
function base(
  v: unknown,
  org: string,
  account: string,
): v is Record<string, unknown> {
  return (
    record(v) &&
    v['organizationId'] === org &&
    v['accountId'] === account &&
    stamp(v['checkedAt']) !== null &&
    integer(v['revision']) &&
    recipient(v['recipient'], account)
  );
}
export function validIssue(
  v: unknown,
  org: string,
  account: string,
  actor: string,
  team = '',
  risk = '',
): v is IssueContext {
  if (
    !exact(
      v,
      'organizationId accountId checkedAt revision recipient selection canIssue blockers',
    ) ||
    !base(v, org, account) ||
    !record(v['recipient']) ||
    v['recipient']['membershipPresent'] !== true
  )
    return false;
  const expected: string[] = [];
  if (account === actor) expected.push('SELF_ISSUANCE');
  if (!v['recipient']['enabled']) expected.push('RECIPIENT_DISABLED');
  const s = v['selection'];
  if (team) {
    if (
      !record(s) ||
      !exact(
        s,
        'teamId teamName teamStatus actionType riskLevel unexpiredCount',
      ) ||
      s['teamId'] !== team ||
      !text(s['teamName']) ||
      !['DRAFT', 'ACTIVE', 'ARCHIVED'].includes(s['teamStatus'] as string) ||
      s['actionType'] !== 'PUBLISH_PREVIEW' ||
      s['riskLevel'] !== risk ||
      !risks.includes(risk as (typeof risks)[number]) ||
      !integer(s['unexpiredCount'], Number.MAX_SAFE_INTEGER)
    )
      return false;
    if (s['teamStatus'] === 'ARCHIVED') expected.push('TEAM_ARCHIVED');
    if (s['unexpiredCount'] > 0) expected.push('DUPLICATE_SCOPE');
  } else if (s !== null || risk) return false;
  return (
    Array.isArray(v['blockers']) &&
    JSON.stringify(v['blockers']) === JSON.stringify(expected) &&
    v['canIssue'] === (expected.length === 0)
  );
}
export function validRevoke(
  v: unknown,
  org: string,
  account: string,
  grant: string,
): v is RevokeContext {
  if (
    !exact(
      v,
      'organizationId accountId checkedAt revision recipient grant grantFingerprint remainingScopeGrants canRevoke blockers',
    ) ||
    !base(v, org, account) ||
    typeof v['grantFingerprint'] !== 'string' ||
    !/^[a-f0-9]{64}$/.test(v['grantFingerprint']) ||
    !integer(v['remainingScopeGrants'], Number.MAX_SAFE_INTEGER)
  )
    return false;
  const g = v['grant'];
  if (
    !record(g) ||
    !exact(
      g,
      'id organizationId accountId teamId teamName actionType riskLevel expiresAt revokedAt status',
    ) ||
    g['id'] !== grant ||
    g['organizationId'] !== org ||
    g['accountId'] !== account ||
    !uuid(g['teamId']) ||
    !text(g['teamName']) ||
    !text(g['actionType'], 100) ||
    !['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(g['riskLevel'] as string) ||
    stamp(g['expiresAt']) === null ||
    !(g['revokedAt'] === null || stamp(g['revokedAt']) !== null)
  )
    return false;
  const state =
    g['revokedAt'] !== null
      ? 'REVOKED'
      : stamp(g['expiresAt'])! <= stamp(v['checkedAt'])!
        ? 'EXPIRED'
        : 'UNEXPIRED';
  return (
    g['status'] === state &&
    v['canRevoke'] === (g['revokedAt'] === null) &&
    JSON.stringify(v['blockers']) ===
      JSON.stringify(g['revokedAt'] === null ? [] : ['ALREADY_REVOKED'])
  );
}
export function validResult(
  v: unknown,
  org: string,
  account: string,
  actor: string,
  key: string,
  operation: Operation,
  revision: number,
  grant?: string,
): v is Result {
  if (
    !record(v) ||
    !exact(
      v,
      'operation organizationId accountId actorId actorName idempotencyKey grantId eventId revision recordedAt expiresAt revokedAt',
    ) ||
    v['organizationId'] !== org ||
    v['accountId'] !== account ||
    v['actorId'] !== actor ||
    v['idempotencyKey'] !== key ||
    v['operation'] !== operation ||
    v['revision'] !== revision + 1 ||
    !integer(v['revision']) ||
    !uuid(v['grantId']) ||
    (grant && v['grantId'] !== grant) ||
    !uuid(v['eventId']) ||
    !text(v['actorName']) ||
    stamp(v['recordedAt']) === null ||
    stamp(v['expiresAt']) === null
  )
    return false;
  return operation === 'ISSUE'
    ? v['revokedAt'] === null &&
        stamp(v['expiresAt'])! > stamp(v['recordedAt'])! &&
        stamp(v['expiresAt'])! - stamp(v['recordedAt'])! <=
          168n * 3600000000000n
    : v['revokedAt'] === v['recordedAt'];
}
export function validHistory(
  v: unknown,
  org: string,
  account: string,
  grant: string,
  page: number,
): v is History {
  return (
    record(v) &&
    exact(
      v,
      'organizationId accountId grantId checkedAt items page size total',
    ) &&
    v['organizationId'] === org &&
    v['accountId'] === account &&
    v['grantId'] === grant &&
    stamp(v['checkedAt']) !== null &&
    v['page'] === page &&
    v['size'] === 20 &&
    integer(v['total'], Number.MAX_SAFE_INTEGER) &&
    Array.isArray(v['items']) &&
    v['items'].length === Math.min(20, Math.max(0, v['total'] - page * 20)) &&
    new Set(v['items'].map(i => (record(i) ? i['id'] : null))).size ===
      v['items'].length &&
    v['items'].every(
      i =>
        record(i) &&
        exact(i, 'id operation actorId actorName reason recordedAt revision') &&
        uuid(i['id']) &&
        ['ISSUE', 'REVOKE'].includes(i['operation'] as string) &&
        uuid(i['actorId']) &&
        text(i['actorName']) &&
        typeof i['reason'] === 'string' &&
        validReason(i['reason']) &&
        i['reason'] === reasonText(i['reason']) &&
        stamp(i['recordedAt']) !== null &&
        integer(i['revision']) &&
        i['revision'] > 0,
    )
  );
}
@Injectable({providedIn: 'root'})
export class GrantManagementService {
  private readonly http = inject(HttpClient);
  private root(org: string, account: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/members/${encodeURIComponent(account)}/approval-grants`;
  }
  workspace(org: string) {
    return this.http
      .get<{
        data: {id: string; name: string; role: string};
      }>(`/api/v1/auth/workspaces/${encodeURIComponent(org)}`)
      .pipe(timeout(30000));
  }
  issueContext(org: string, account: string, team = '', risk = '') {
    return this.http
      .get<{
        data: unknown;
      }>(this.root(org, account) + '/management-context', {
        params: team ? {teamId: team, riskLevel: risk} : {},
      })
      .pipe(timeout(30000));
  }
  revokeContext(org: string, account: string, grant: string) {
    return this.http
      .get<{
        data: unknown;
      }>(
        this.root(org, account) +
          `/${encodeURIComponent(grant)}/revocation-context`,
      )
      .pipe(timeout(30000));
  }
  history(org: string, account: string, grant: string, page: number) {
    return this.http
      .get<{
        data: unknown;
      }>(
        this.root(org, account) +
          `/${encodeURIComponent(grant)}/management-history`,
        {params: {page}},
      )
      .pipe(timeout(30000));
  }
  receipt(org: string, account: string, key: string, operation: Operation) {
    return this.http
      .get<{
        data: unknown;
      }>(
        this.root(org, account) +
          `/management-receipts/${encodeURIComponent(key)}`,
        {params: {operation}},
      )
      .pipe(timeout(30000));
  }
  send(
    org: string,
    account: string,
    grant: string,
    key: string,
    body: Command,
  ) {
    return this.http
      .get<{data: {headerName: string; token: string}}>('/api/v1/auth/csrf')
      .pipe(
        timeout(30000),
        switchMap(c => {
          if (!c?.data?.headerName || !c.data.token)
            throw new Error('CSRF unavailable');
          return this.http
            .post<{
              data: unknown;
            }>(
              this.root(org, account) +
                (grant ? `/${encodeURIComponent(grant)}/revoke` : ''),
              body,
              {
                headers: new HttpHeaders()
                  .set(c.data.headerName, c.data.token)
                  .set('Idempotency-Key', key),
              },
            )
            .pipe(timeout(30000));
        }),
      );
  }
}

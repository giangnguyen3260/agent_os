import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';
import {uuid} from '../teams/team.service';

export const grantStatuses = [
  'ALL',
  'UNEXPIRED',
  'EXPIRED',
  'REVOKED',
] as const;
export type GrantStatus = (typeof grantStatuses)[number];
export interface Member {
  accountId: string;
  displayName: string;
  email: string;
  role: 'ADMIN' | 'MEMBER';
  enabled: boolean;
  isSelf: boolean;
}
export interface MemberRow extends Member {
  grantCounts: {unexpired: number; expired: number; revoked: number};
}
export interface Grant {
  id: string;
  organizationId: string;
  accountId: string;
  teamId: string;
  teamName: string;
  actionType: string;
  riskLevel: string;
  expiresAt: string;
  revokedAt: string | null;
  status: Exclude<GrantStatus, 'ALL'>;
}
interface Page {
  organizationId: string;
  generatedAt: string;
  page: number;
  size: number;
  total: number;
}
export interface MemberPage extends Page {
  items: MemberRow[];
}
export interface GrantPage extends Page {
  member: Member;
  items: Grant[];
}
const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
const keys = (v: unknown, names: string): boolean =>
  record(v) &&
  Object.keys(v).sort().join(',') === names.split(' ').sort().join(',');
const count = (v: unknown): v is number =>
  Number.isSafeInteger(v) && (v as number) >= 0;
const text = (v: unknown, max: number): v is string =>
  typeof v === 'string' && !!v.trim() && v.length <= max;
// Java Character.isWhitespace / String.strip, excluding non-breaking spaces.
export const stripMemberQuery = (v: string): string =>
  v.replace(
    // eslint-disable-next-line no-control-regex -- Match Java edge whitespace exactly.
    /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g,
    '',
  );
// Instant JSON is UTC; preserve PostgreSQL microseconds rather than Date's milliseconds.
function instant(v: unknown): bigint | null {
  if (typeof v !== 'string') return null;
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(
    v,
  );
  if (!match) return null;
  const seconds = Date.parse(match[1] + 'Z');
  if (
    !Number.isFinite(seconds) ||
    new Date(seconds).toISOString().slice(0, 19) !== match[1]
  )
    return null;
  return BigInt(seconds) * 1000000n + BigInt((match[2] || '').padEnd(9, '0'));
}
const date = (v: unknown): v is string => instant(v) !== null;
const memberKeys = 'accountId displayName email role enabled isSelf';
function member(v: unknown, actor: string, withCounts = false): v is Member {
  if (!record(v) || !keys(v, memberKeys + (withCounts ? ' grantCounts' : '')))
    return false;
  return (
    uuid(v['accountId']) &&
    text(v['displayName'], 255) &&
    text(v['email'], 254) &&
    ['ADMIN', 'MEMBER'].includes(v['role'] as string) &&
    typeof v['enabled'] === 'boolean' &&
    v['isSelf'] === (v['accountId'] === actor)
  );
}
function page(
  v: unknown,
  org: string,
  index: number,
): v is Page & {items: unknown[]} {
  return (
    record(v) &&
    v['organizationId'] === org &&
    date(v['generatedAt']) &&
    v['page'] === index &&
    v['size'] === 20 &&
    count(v['total']) &&
    Array.isArray(v['items']) &&
    v['items'].length === Math.min(20, Math.max(0, v['total'] - index * 20))
  );
}
export function validMembers(
  v: unknown,
  org: string,
  actor: string,
  index: number,
  role: string,
): v is MemberPage {
  if (
    !keys(v, 'organizationId generatedAt items page size total') ||
    !page(v, org, index)
  )
    return false;
  return (
    new Set(v.items.map(i => (record(i) ? i['accountId'] : null))).size ===
      v.items.length &&
    v.items.every(
      i =>
        member(i, actor, true) &&
        (!role || i.role === role) &&
        record(i) &&
        keys(i['grantCounts'], 'unexpired expired revoked') &&
        Object.values(i['grantCounts'] as object).every(count),
    )
  );
}
export function validGrants(
  v: unknown,
  org: string,
  actor: string,
  account: string,
  index: number,
  status: GrantStatus,
): v is GrantPage {
  if (
    !keys(v, 'organizationId generatedAt member items page size total') ||
    !page(v, org, index) ||
    !record(v) ||
    !member(v['member'], actor) ||
    v['member'].accountId !== account
  )
    return false;
  return (
    new Set(v.items.map(i => (record(i) ? i['id'] : null))).size ===
      v.items.length &&
    v.items.every(i => {
      if (
        !record(i) ||
        !keys(
          i,
          'id organizationId accountId teamId teamName actionType riskLevel expiresAt revokedAt status',
        ) ||
        !uuid(i['id']) ||
        i['organizationId'] !== org ||
        i['accountId'] !== account ||
        !uuid(i['teamId']) ||
        !text(i['teamName'], 255) ||
        !text(i['actionType'], 100) ||
        !['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].includes(
          i['riskLevel'] as string,
        ) ||
        !date(i['expiresAt']) ||
        !(i['revokedAt'] === null || date(i['revokedAt']))
      )
        return false;
      const lifecycle =
        i['revokedAt'] !== null
          ? 'REVOKED'
          : instant(i['expiresAt'])! <= instant(v.generatedAt)!
            ? 'EXPIRED'
            : 'UNEXPIRED';
      return (
        i['status'] === lifecycle && (status === 'ALL' || status === lifecycle)
      );
    })
  );
}
@Injectable({providedIn: 'root'})
export class MemberAccessService {
  private readonly http = inject(HttpClient);
  members(org: string, page: number, query: string, role: string) {
    return this.http
      .get<{data: unknown}>(
        `/api/v1/organizations/${encodeURIComponent(org)}/members`,
        {
          params: {page, query, role},
        },
      )
      .pipe(timeout(30000));
  }
  grants(org: string, account: string, page: number, status: GrantStatus) {
    return this.http
      .get<{
        data: unknown;
      }>(
        `/api/v1/organizations/${encodeURIComponent(org)}/members/${encodeURIComponent(account)}/approval-grants`,
        {params: {page, status}},
      )
      .pipe(timeout(30000));
  }
}

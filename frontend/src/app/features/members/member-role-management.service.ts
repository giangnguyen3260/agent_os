import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {switchMap, timeout} from 'rxjs';
import {uuid} from '../teams/team.service';
import {
  exact,
  reasonText,
  validReason as validGrantReason,
  stamp,
} from './grant-management.service';
export {exact, reasonText};
export function validReason(value: string): boolean {
  return (
    validGrantReason(value) &&
    [...value].every(character => {
      const point = character.codePointAt(0)!;
      return point < 0xd800 || point > 0xdfff;
    })
  );
}
export type Role = 'ADMIN' | 'MEMBER';
export interface Context {
  organizationId: string;
  accountId: string;
  checkedAt: string;
  revision: number;
  member: {
    accountId: string;
    displayName: string;
    enabled: boolean;
    currentRole: Role;
    isSelf: boolean;
  };
  selectedRole: Role | null;
  enabledAdminCount: number;
  canChange: boolean;
  blockers: string[];
}
export interface Command {
  expectedRevision: number;
  expectedCurrentRole: Role;
  newRole: Role;
  reason: string;
  acknowledgement: true;
}
export interface Correlation {
  org: string;
  account: string;
  actor: string;
  key: string;
  operation: 'CHANGE_ROLE';
  revision: number;
  beforeRole: Role;
  afterRole: Role;
}
export interface Result {
  operation: 'CHANGE_ROLE';
  organizationId: string;
  accountId: string;
  actorId: string;
  actorName: string;
  idempotencyKey: string;
  eventId: string;
  revision: number;
  recordedAt: string;
  beforeRole: Role;
  afterRole: Role;
}
export interface History {
  organizationId: string;
  accountId: string;
  checkedAt: string;
  items: {
    id: string;
    actorId: string;
    actorName: string;
    beforeRole: Role;
    afterRole: Role;
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
const integer = (v: unknown, max = 2147483647): v is number =>
  Number.isSafeInteger(v) && (v as number) >= 0 && (v as number) <= max;
const text = (v: unknown): v is string =>
  typeof v === 'string' && !!v.trim() && [...v].length <= 255;
export const role = (v: unknown): v is Role => v === 'ADMIN' || v === 'MEMBER';
const micro = (v: unknown) =>
  typeof v === 'string' &&
  /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{6}Z$/.test(v) &&
  stamp(v) !== null;
export function validContext(
  v: unknown,
  org: string,
  account: string,
  actor: string,
  selected: Role | null = null,
): v is Context {
  if (
    !record(v) ||
    !exact(
      v,
      'organizationId accountId checkedAt revision member selectedRole enabledAdminCount canChange blockers',
    ) ||
    !uuid(org) ||
    !uuid(account) ||
    !uuid(actor) ||
    v['organizationId'] !== org ||
    v['accountId'] !== account ||
    stamp(v['checkedAt']) === null ||
    !integer(v['revision']) ||
    !integer(v['enabledAdminCount'], Number.MAX_SAFE_INTEGER) ||
    v['enabledAdminCount'] < 1 ||
    v['selectedRole'] !== selected
  )
    return false;
  const m = v['member'];
  if (
    !record(m) ||
    !exact(m, 'accountId displayName enabled currentRole isSelf') ||
    m['accountId'] !== account ||
    !text(m['displayName']) ||
    typeof m['enabled'] !== 'boolean' ||
    !role(m['currentRole']) ||
    m['isSelf'] !== (actor === account)
  )
    return false;
  const blockers: string[] = [];
  if (m['isSelf']) blockers.push('SELF_CHANGE');
  if (!m['enabled']) blockers.push('TARGET_DISABLED');
  if (selected === m['currentRole']) blockers.push('NO_CHANGE');
  if (
    selected === 'MEMBER' &&
    m['currentRole'] === 'ADMIN' &&
    v['enabledAdminCount'] <= 1
  )
    blockers.push('LAST_ENABLED_ADMIN');
  return (
    JSON.stringify(v['blockers']) === JSON.stringify(blockers) &&
    v['canChange'] === (selected !== null && blockers.length === 0)
  );
}
export function validResult(v: unknown, p: Correlation): v is Result {
  return (
    record(v) &&
    exact(
      v,
      'operation organizationId accountId actorId actorName idempotencyKey eventId revision recordedAt beforeRole afterRole',
    ) &&
    [p.org, p.account, p.actor, p.key].every(uuid) &&
    p.operation === 'CHANGE_ROLE' &&
    role(p.beforeRole) &&
    role(p.afterRole) &&
    p.beforeRole !== p.afterRole &&
    integer(p.revision) &&
    p.revision < 2147483647 &&
    v['organizationId'] === p.org &&
    v['accountId'] === p.account &&
    v['actorId'] === p.actor &&
    v['idempotencyKey'] === p.key &&
    v['operation'] === p.operation &&
    v['beforeRole'] === p.beforeRole &&
    v['afterRole'] === p.afterRole &&
    v['revision'] === p.revision + 1 &&
    uuid(v['eventId']) &&
    text(v['actorName']) &&
    micro(v['recordedAt'])
  );
}
export function validHistory(
  v: unknown,
  org: string,
  account: string,
  page: number,
): v is History {
  if (
    !record(v) ||
    !exact(v, 'organizationId accountId checkedAt items page size total') ||
    !uuid(org) ||
    !uuid(account) ||
    !integer(page, 100000) ||
    v['organizationId'] !== org ||
    v['accountId'] !== account ||
    stamp(v['checkedAt']) === null ||
    v['page'] !== page ||
    v['size'] !== 20 ||
    !integer(v['total'], Number.MAX_SAFE_INTEGER) ||
    !Array.isArray(v['items']) ||
    v['items'].length !== Math.min(20, Math.max(0, v['total'] - page * 20))
  )
    return false;
  const ids = new Set<string>();
  let previous: {time: bigint; id: string} | null = null;
  for (const i of v['items']) {
    if (
      !record(i) ||
      !exact(
        i,
        'id actorId actorName beforeRole afterRole reason recordedAt revision',
      ) ||
      !uuid(i['id']) ||
      ids.has(i['id']) ||
      !uuid(i['actorId']) ||
      !text(i['actorName']) ||
      !role(i['beforeRole']) ||
      !role(i['afterRole']) ||
      i['beforeRole'] === i['afterRole'] ||
      typeof i['reason'] !== 'string' ||
      !validReason(i['reason']) ||
      i['reason'] !== reasonText(i['reason']) ||
      !micro(i['recordedAt']) ||
      !integer(i['revision']) ||
      i['revision'] === 0
    )
      return false;
    const time = stamp(i['recordedAt'])!;
    if (
      previous &&
      (time > previous.time ||
        (time === previous.time && i['id'] >= previous.id))
    )
      return false;
    ids.add(i['id']);
    previous = {time, id: i['id']};
  }
  return true;
}
@Injectable({providedIn: 'root'})
export class MemberRoleManagementService {
  private readonly http = inject(HttpClient);
  private root(org: string, account: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/members/${encodeURIComponent(account)}/role-management`;
  }
  workspace(org: string) {
    return this.http
      .get<{
        data: {id: string; name: string; role: string};
      }>(`/api/v1/auth/workspaces/${encodeURIComponent(org)}`)
      .pipe(timeout(30000));
  }
  context(org: string, account: string, selected: Role | null = null) {
    return this.http
      .get<{
        data: unknown;
      }>(this.root(org, account) + '/context', {
        params: selected ? {newRole: selected} : {},
      })
      .pipe(timeout(30000));
  }
  history(org: string, account: string, page: number) {
    return this.http
      .get<{
        data: unknown;
      }>(this.root(org, account) + '/history', {params: {page}})
      .pipe(timeout(30000));
  }
  receipt(org: string, account: string, key: string) {
    return this.http
      .get<{
        data: unknown;
      }>(this.root(org, account) + '/receipts/' + encodeURIComponent(key))
      .pipe(timeout(30000));
  }
  send(org: string, account: string, key: string, body: Command) {
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
            }>(this.root(org, account), body, {
              headers: new HttpHeaders()
                .set(c.data.headerName, c.data.token)
                .set('Idempotency-Key', key),
            })
            .pipe(timeout(30000));
        }),
      );
  }
}

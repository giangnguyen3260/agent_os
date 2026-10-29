/* eslint-disable no-control-regex -- Wire rejects forbidden control characters. */
export const uuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
    v,
  );
export const exact = (v: unknown, keys: string): v is Record<string, unknown> =>
  !!v &&
  typeof v === 'object' &&
  !Array.isArray(v) &&
  Reflect.ownKeys(v).length === keys.split(' ').length &&
  keys.split(' ').every(k => Object.prototype.hasOwnProperty.call(v, k));
export const strip = (v: string) =>
  v.replace(
    /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g,
    '',
  );
export function text(v: unknown, max: number, multiline = false): v is string {
  return (
    typeof v === 'string' &&
    !(
      multiline
        ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/
        : /[\u0000-\u001f\u007f-\u009f]/
    ).test(v) &&
    !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(
      v,
    ) &&
    [...v].length <= max
  );
}
export const revision = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= 2147483647;
export const fingerprint = (v: unknown) =>
  typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
export function timestamp(v: unknown): boolean {
  if (
    typeof v !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(v)
  )
    return false;
  const base = v.slice(0, 19) + 'Z';
  return (
    Number.isFinite(Date.parse(base)) &&
    new Date(base).toISOString().slice(0, 19) === v.slice(0, 19)
  );
}
export interface Scope {
  organizationId: string;
  teamId: string;
  missionId: string;
  taskId: string;
}

export interface Agent {
  id: string;
  name: string;
  level: string;
  status: string;
}
export interface Candidate extends Agent {
  fingerprint: string;
}
export interface Values {
  state: 'UNASSIGNED' | 'ASSIGNED' | 'UNAVAILABLE';
  agent: Agent | null;
}
export interface Body {
  expectedRevision: number;
  expectedFingerprint: string;
  ownerAgentId: string | null;
  ownerFingerprint: string | null;
}
export interface Context extends Scope {
  checkedAt: string;
  revision: number;
  fingerprint: string;
  teamName: string;
  missionTitle: string;
  taskTitle: string;
  taskStatus: string;
  owner: Values;
  canEdit: boolean;
  blockers: string[];
}
export interface Receipt extends Scope {
  eventId: string;
  revision: number;
  committedAt: string;
  before: Values;
  after: Values;
}
export interface Locator extends Scope {
  actor: string;
  key: string;
}
export interface CandidatePage extends Scope {
  page: number;
  pageSize: number;
  total: number;
  query: string;
  items: Candidate[];
}
export const nonblank = (v: unknown, n: number) =>
  text(v, n) && strip(v).length > 0;
export function agent(v: unknown): v is Agent {
  return (
    exact(v, 'id name level status') &&
    uuid(v['id']) &&
    nonblank(v['name'], 255) &&
    nonblank(v['level'], 50) &&
    nonblank(v['status'], 50)
  );
}
export function candidate(v: unknown): v is Candidate {
  if (!exact(v, 'id name level status fingerprint')) return false;
  const {fingerprint: f, ...a} = v;
  return (
    agent(a) &&
    fingerprint(f) &&
    ['DRAFT', 'ACTIVE'].includes(a['status'] as string)
  );
}
export function owner(v: unknown): v is Values {
  return (
    exact(v, 'state agent') &&
    ((v['state'] === 'ASSIGNED' && agent(v['agent'])) ||
      (['UNASSIGNED', 'UNAVAILABLE'].includes(v['state'] as string) &&
        v['agent'] === null))
  );
}
export function sameValues(a: Values, b: Values) {
  return (
    a.state === b.state &&
    (a.agent === null
      ? b.agent === null
      : b.agent !== null &&
        (['id', 'name', 'level', 'status'] as const).every(
          k => a.agent![k] === b.agent![k],
        ))
  );
}
export function validBody(v: unknown): v is Body {
  return (
    exact(
      v,
      'expectedRevision expectedFingerprint ownerAgentId ownerFingerprint',
    ) &&
    revision(v['expectedRevision']) &&
    fingerprint(v['expectedFingerprint']) &&
    (v['ownerAgentId'] === null
      ? v['ownerFingerprint'] === null
      : uuid(v['ownerAgentId']) && fingerprint(v['ownerFingerprint']))
  );
}
export function sameScope(v: Scope, s: Scope) {
  return ['organizationId', 'teamId', 'missionId', 'taskId'].every(
    k =>
      uuid(v[k as keyof Scope]) && v[k as keyof Scope] === s[k as keyof Scope],
  );
}
const blockers = [
  'TASK_NOT_DRAFT',
  'TEAM_ARCHIVED',
  'MISSION_NOT_EDITABLE',
  'INTAKE_PAUSED',
  'REVISION_EXHAUSTED',
  'UNKNOWN_STATE',
];
export function validContext(v: unknown, s: Scope): v is Context {
  if (
    !exact(
      v,
      'organizationId teamId missionId taskId checkedAt revision fingerprint teamName missionTitle taskTitle taskStatus owner canEdit blockers',
    )
  )
    return false;
  const c = v as unknown as Context;
  return (
    sameScope(c, s) &&
    Array.isArray(c.blockers) &&
    ([
      'DRAFT',
      'READY',
      'RUNNING',
      'WAITING',
      'BLOCKED',
      'IN_REVIEW',
      'COMPLETED',
      'FAILED',
      'CANCELLED',
    ].includes(c.taskStatus)
      ? c.taskStatus === 'DRAFT' || c.blockers.includes('TASK_NOT_DRAFT')
      : c.blockers.includes('UNKNOWN_STATE')) &&
    timestamp(c.checkedAt) &&
    revision(c.revision) &&
    fingerprint(c.fingerprint) &&
    nonblank(c.teamName, 255) &&
    nonblank(c.missionTitle, 255) &&
    typeof c.taskStatus === 'string' &&
    text(c.taskTitle, 255) &&
    owner(c.owner) &&
    Array.isArray(c.blockers) &&
    c.blockers.every(
      (x, i) =>
        blockers.includes(x) &&
        (i === 0 || blockers.indexOf(c.blockers[i - 1]) < blockers.indexOf(x)),
    ) &&
    typeof c.canEdit === 'boolean' &&
    c.canEdit === (c.blockers.length === 0) &&
    (!c.canEdit || (c.taskStatus === 'DRAFT' && c.revision < 2147483647))
  );
}

export function validPage(
  v: unknown,
  s: Scope,
  page: number,
  query: string,
): v is CandidatePage {
  if (
    !exact(
      v,
      'organizationId teamId missionId taskId page pageSize total query items',
    )
  )
    return false;
  const p = v as unknown as CandidatePage;
  return (
    sameScope(p, s) &&
    p.page === page &&
    Number.isInteger(page) &&
    page >= 0 &&
    page <= 100000 &&
    p.pageSize === 20 &&
    Number.isSafeInteger(p.total) &&
    p.total >= 0 &&
    p.query === query &&
    text(query, 100) &&
    strip(query) === query &&
    Array.isArray(p.items) &&
    p.items.length <= 20 &&
    p.items.every(candidate) &&
    new Set(p.items.map(x => x.id)).size === p.items.length &&
    p.items.length === Math.min(20, Math.max(0, p.total - page * 20))
  );
}
export function validReceipt(
  v: unknown,
  s: Scope,
  b?: Body,
  before?: Values,
): v is Receipt {
  if (
    !exact(
      v,
      'organizationId teamId missionId taskId eventId revision committedAt before after',
    )
  )
    return false;
  const r = v as unknown as Receipt;
  return (
    sameScope(r, s) &&
    uuid(r.eventId) &&
    revision(r.revision) &&
    r.revision > 0 &&
    timestamp(r.committedAt) &&
    owner(r.before) &&
    owner(r.after) &&
    r.after.state !== 'UNAVAILABLE' &&
    (!r.after.agent || ['DRAFT', 'ACTIVE'].includes(r.after.agent.status)) &&
    !sameValues(r.before, r.after) &&
    (!b ||
      (r.revision === b.expectedRevision + 1 &&
        (r.after.agent?.id ?? null) === b.ownerAgentId)) &&
    (!before || sameValues(before, r.before))
  );
}

export const newOwnerSnapshot = (v: unknown): v is Values =>
  owner(v) &&
  v.state !== 'UNAVAILABLE' &&
  (!v.agent || ['DRAFT', 'ACTIVE'].includes(v.agent.status));

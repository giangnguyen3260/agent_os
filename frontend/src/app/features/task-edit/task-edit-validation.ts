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
        ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/
        : /[\u0000-\u001f\u007f]/
    ).test(v) &&
    !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(
      v,
    ) &&
    [...v].length <= max
  );
}
const revision = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= 2147483647;
const fingerprint = (v: unknown) =>
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
export interface Values {
  title: string;
  description: string | null;
  acceptanceCriteria: string | null;
  priority: string | null;
}
export interface Body {
  expectedRevision: number;
  expectedFingerprint: string;
  title: string;
  description: string;
  acceptanceCriteria: string;
  priority: string;
}
export interface Context extends Scope {
  checkedAt: string;
  revision: number;
  fingerprint: string;
  teamName: string;
  missionTitle: string;
  taskStatus: string;
  values: Values;
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
export const fields = [
  'title',
  'description',
  'acceptanceCriteria',
  'priority',
] as const;
export const labels = [
  'Title',
  'Description / input',
  'Acceptance criteria',
  'Priority',
];
export function values(v: unknown, complete = false): v is Values {
  if (
    !exact(v, 'title description acceptanceCriteria priority') ||
    typeof v['title'] !== 'string' ||
    ![v['description'], v['acceptanceCriteria'], v['priority']].every(
      x => x === null || typeof x === 'string',
    )
  )
    return false;
  return (
    !complete ||
    (text(v['title'], 255) &&
      text(v['description'], 8000, true) &&
      text(v['acceptanceCriteria'], 4000, true) &&
      [v['title'], v['description'], v['acceptanceCriteria']].every(
        x => typeof x === 'string' && strip(x) === x && x.length > 0,
      ) &&
      typeof v['priority'] === 'string' &&
      ['LOW', 'NORMAL', 'HIGH', 'URGENT'].includes(v['priority']))
  );
}
export function validBody(v: unknown): v is Body {
  if (
    !exact(
      v,
      'expectedRevision expectedFingerprint title description acceptanceCriteria priority',
    )
  )
    return false;
  const desired = {
    title: v['title'],
    description: v['description'],
    acceptanceCriteria: v['acceptanceCriteria'],
    priority: v['priority'],
  };
  return (
    revision(v['expectedRevision']) &&
    fingerprint(v['expectedFingerprint']) &&
    values(desired, true) &&
    new TextEncoder().encode(JSON.stringify(v)).length <= 65536
  );
}
export function sameValues(a: Values, b: Values) {
  return fields.every(k => a[k] === b[k]);
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
      'organizationId teamId missionId taskId checkedAt revision fingerprint teamName missionTitle taskStatus values canEdit blockers',
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
    typeof c.teamName === 'string' &&
    typeof c.missionTitle === 'string' &&
    typeof c.taskStatus === 'string' &&
    values(c.values) &&
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
export function validReceipt(
  v: unknown,
  s: Scope,
  body?: Body,
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
    values(r.before) &&
    values(r.after, true) &&
    !sameValues(r.before, r.after) &&
    (!body ||
      (r.revision === body.expectedRevision + 1 &&
        sameValues(r.after, body))) &&
    (!before || sameValues(r.before, before))
  );
}

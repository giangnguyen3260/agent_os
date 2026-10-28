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
}
export interface Body {
  expectedVersion: string;
  expectedFingerprint: string;
  paused: boolean;
}
export interface Context extends Scope {
  checkedAt: string;
  teamName: string;
  missionTitle: string;
  teamStatus: string;
  missionStatus: string;
  intakePaused: boolean;
  intakeVersion: string;
  fingerprint: string;
  canChange: boolean;
  blockers: string[];
}
export interface Receipt extends Scope {
  eventId: string;
  versionBefore: string;
  versionAfter: string;
  pausedBefore: boolean;
  pausedAfter: boolean;
  committedAt: string;
}
export interface Locator extends Scope {
  actor: string;
  key: string;
}
export const MAX_VERSION = '9223372036854775807';
export const version = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^(0|[1-9][0-9]*)$/.test(v) &&
  (v.length < 19 || (v.length === 19 && v <= MAX_VERSION));
const fingerprint = (v: unknown) =>
  typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
export function missionScope3(v: Scope, s: Scope) {
  return (['organizationId', 'teamId', 'missionId'] as const).every(
    k => uuid(v[k]) && v[k] === s[k],
  );
}
export const sameScope = missionScope3;
export function validBody(v: unknown): v is Body {
  return (
    exact(v, 'expectedVersion expectedFingerprint paused') &&
    version(v['expectedVersion']) &&
    v['expectedVersion'] !== MAX_VERSION &&
    fingerprint(v['expectedFingerprint']) &&
    typeof v['paused'] === 'boolean'
  );
}
export function validContext(v: unknown, s: Scope): v is Context {
  if (
    !exact(
      v,
      'organizationId teamId missionId checkedAt teamName missionTitle teamStatus missionStatus intakePaused intakeVersion fingerprint canChange blockers',
    )
  )
    return false;
  const c = v as unknown as Context;
  if (
    !missionScope3(c, s) ||
    !timestamp(c.checkedAt) ||
    !text(c.teamName, Infinity) ||
    !text(c.missionTitle, Infinity) ||
    !text(c.teamStatus, Infinity) ||
    !text(c.missionStatus, Infinity) ||
    !version(c.intakeVersion) ||
    !fingerprint(c.fingerprint) ||
    typeof c.intakePaused !== 'boolean' ||
    typeof c.canChange !== 'boolean' ||
    !Array.isArray(c.blockers)
  )
    return false;
  const expected: string[] = [];
  if (c.teamStatus === 'ARCHIVED') expected.push('TEAM_ARCHIVED');
  const missions = ['CREATED', 'READY', 'RUNNING', 'WAITING_APPROVAL'];
  if (
    ['PLANNING', 'REVIEW', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(
      c.missionStatus,
    )
  )
    expected.push('MISSION_NOT_EDITABLE');
  if (c.intakeVersion === MAX_VERSION) expected.push('REVISION_EXHAUSTED');
  if (
    !['DRAFT', 'ACTIVE', 'ARCHIVED'].includes(c.teamStatus) ||
    ![
      ...missions,
      'PLANNING',
      'REVIEW',
      'COMPLETED',
      'FAILED',
      'CANCELLED',
    ].includes(c.missionStatus)
  )
    expected.push('UNKNOWN_STATE');
  return (
    JSON.stringify(c.blockers) === JSON.stringify(expected) &&
    c.canChange === (expected.length === 0)
  );
}
export function validReceipt(
  v: unknown,
  s: Scope,
  body?: Body,
  before?: boolean,
): v is Receipt {
  if (
    !exact(
      v,
      'organizationId teamId missionId eventId versionBefore versionAfter pausedBefore pausedAfter committedAt',
    )
  )
    return false;
  const r = v as unknown as Receipt;
  return (
    missionScope3(r, s) &&
    uuid(r.eventId) &&
    timestamp(r.committedAt) &&
    version(r.versionBefore) &&
    version(r.versionAfter) &&
    BigInt(r.versionAfter) === BigInt(r.versionBefore) + 1n &&
    typeof r.pausedBefore === 'boolean' &&
    typeof r.pausedAfter === 'boolean' &&
    r.pausedBefore !== r.pausedAfter &&
    (!body ||
      (r.versionBefore === body.expectedVersion &&
        r.pausedAfter === body.paused)) &&
    (before === undefined || r.pausedBefore === before)
  );
}

/* eslint-disable no-control-regex -- Reject C0/DEL and implement Java strip boundaries exactly. */
/** Wire validation for TEAM_SETTINGS_CONTRACT.md. Fingerprints stay opaque. */
export const MAX_REVISION = 2147483647;
export const settingsUuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
    v,
  );
export function exact(v: unknown, keys: string): v is Record<string, unknown> {
  return (
    !!v &&
    typeof v === 'object' &&
    !Array.isArray(v) &&
    Reflect.ownKeys(v).length === keys.split(' ').length &&
    keys.split(' ').every(k => Object.prototype.hasOwnProperty.call(v, k))
  );
}
export function strip(v: string): string {
  return v.replace(
    /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g,
    '',
  );
}
export function safeText(
  v: unknown,
  max: number,
  required = false,
): v is string {
  return (
    typeof v === 'string' &&
    !/[\u0000-\u001f\u007f]/.test(v) &&
    !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(
      v,
    ) &&
    [...v].length <= max &&
    (!required || strip(v).length > 0)
  );
}
const integer = (v: unknown, min = 0, max = MAX_REVISION): v is number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max;
export const budget = (v: unknown): v is number =>
  typeof v === 'number' &&
  Number.isFinite(v) &&
  v >= 0 &&
  v <= 1000000 &&
  Number(v.toFixed(2)) === v;
export function timestamp(v: unknown): v is string {
  if (
    typeof v !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(v)
  )
    return false;
  const date = new Date(v);
  return (
    Number.isFinite(date.getTime()) &&
    date.toISOString() === v.slice(0, 23) + 'Z'
  );
}
const fingerprint = (v: unknown): v is string =>
  typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const codes = (v: unknown): v is string[] =>
  Array.isArray(v) &&
  v.every(c => typeof c === 'string' && /^[A-Z][A-Z0-9_]{0,79}$/.test(c)) &&
  new Set(v).size === v.length;
export interface SettingsState {
  name: string;
  purpose: string | null;
  description: string | null;
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
  managerAgentId: string | null;
  agentIds: string[];
  intake: 'MANUAL' | null;
  concurrency: number | null;
  monthlyBudget: number | null;
  currency: string | null;
}
export interface SettingsAgent {
  id: string;
  name: string;
  level: string;
  status: string;
}
export interface SettingsMember extends SettingsAgent {
  canRemove: boolean;
  removalBlockers: string[];
}
export interface SettingsContext {
  organizationId: string;
  teamId: string;
  checkedAt: string;
  revision: number;
  fingerprint: string;
  team: SettingsState;
  members: SettingsMember[];
  memberTotal: number;
  canEdit: boolean;
  canChangeMembership: boolean;
  blockers: string[];
}
export interface SettingsCandidates {
  organizationId: string;
  teamId: string;
  checkedAt: string;
  items: SettingsAgent[];
  page: number;
  size: number;
  total: number;
}
export interface SettingsBody {
  expectedRevision: number;
  expectedFingerprint: string;
  name: string;
  purpose: string;
  description: string | null;
  managerAgentId: string | null;
  agentIds: string[];
  intake: 'MANUAL';
  concurrency: number;
  monthlyBudget: number;
  acknowledgement: true;
}
export interface SettingsLocator {
  actor: string;
  org: string;
  team: string;
  key: string;
}
export interface SettingsReceipt {
  operation: 'UPDATE_TEAM_SETTINGS';
  organizationId: string;
  teamId: string;
  idempotencyKey: string;
  revision: number;
  eventId: string;
  actorId: string;
  actorName: string;
  recordedAt: string;
  before: SettingsState;
  after: SettingsState;
}
const stateKeys =
  'name purpose description status managerAgentId agentIds intake concurrency monthlyBudget currency';
function ids(v: unknown, sorted: boolean): v is string[] {
  return (
    Array.isArray(v) &&
    v.every(settingsUuid) &&
    new Set(v).size === v.length &&
    (!sorted || v.every((s, i) => !i || v[i - 1] < s))
  );
}
export function validSettingsState(
  v: unknown,
  after = false,
): v is SettingsState {
  if (!exact(v, stateKeys)) return false;
  return (
    safeText(v['name'], 255, true) &&
    (v['purpose'] === null ? !after : safeText(v['purpose'], 2000, after)) &&
    (v['description'] === null || safeText(v['description'], 4000)) &&
    ['DRAFT', 'ACTIVE', 'ARCHIVED'].includes(v['status'] as string) &&
    (v['managerAgentId'] === null || settingsUuid(v['managerAgentId'])) &&
    ids(v['agentIds'], true) &&
    !v['agentIds'].includes(v['managerAgentId'] as string) &&
    (v['intake'] === 'MANUAL' || (!after && v['intake'] === null)) &&
    (integer(v['concurrency'], 1, 1000) ||
      (!after && v['concurrency'] === null)) &&
    (budget(v['monthlyBudget']) || (!after && v['monthlyBudget'] === null)) &&
    (after
      ? v['currency'] === 'USD'
      : v['currency'] === null ||
        (typeof v['currency'] === 'string' && /^[A-Z]{3}$/.test(v['currency'])))
  );
}
export const knownAgent = (a: SettingsAgent) =>
  ['JUNIOR', 'MID', 'SENIOR', 'LEAD', 'MANAGER'].includes(a.level) &&
  ['DRAFT', 'ACTIVE', 'DEGRADED', 'SUSPENDED', 'ARCHIVED'].includes(a.status);
function agent(v: unknown, member: boolean): v is SettingsMember {
  return (
    exact(
      v,
      'id name level status' + (member ? ' canRemove removalBlockers' : ''),
    ) &&
    settingsUuid(v['id']) &&
    safeText(v['name'], 255, true) &&
    safeText(v['level'], 100, true) &&
    safeText(v['status'], 100, true) &&
    (!member ||
      (typeof v['canRemove'] === 'boolean' &&
        codes(v['removalBlockers']) &&
        v['canRemove'] === (v['removalBlockers'].length === 0)))
  );
}
export function validSettingsContext(
  v: unknown,
  org: string,
  team: string,
): v is SettingsContext {
  if (
    !settingsUuid(org) ||
    !settingsUuid(team) ||
    !exact(
      v,
      'organizationId teamId checkedAt revision fingerprint team members memberTotal canEdit canChangeMembership blockers',
    ) ||
    v['organizationId'] !== org ||
    v['teamId'] !== team ||
    !timestamp(v['checkedAt']) ||
    !integer(v['revision']) ||
    !fingerprint(v['fingerprint']) ||
    !validSettingsState(v['team']) ||
    !integer(v['memberTotal'], 0, Number.MAX_SAFE_INTEGER) ||
    !Array.isArray(v['members']) ||
    !v['members'].every(a => agent(a, true)) ||
    v['members'].length !== Math.min(201, v['memberTotal']) ||
    !ids(
      v['members'].map(a => a.id),
      true,
    ) ||
    !codes(v['blockers'])
  )
    return false;
  const s = v['team'];
  const records = v['members'] as SettingsMember[];
  const expected = [
    s.status === 'ARCHIVED' ? 'TEAM_ARCHIVED' : '',
    v['memberTotal'] > 200 ? 'TEAM_SIZE_UNSUPPORTED' : '',
    s.currency !== null && s.currency !== 'USD' ? 'UNSUPPORTED_CURRENCY' : '',
  ].filter(Boolean);
  const members = [
    ...s.agentIds,
    ...(s.managerAgentId ? [s.managerAgentId] : []),
  ].sort();
  return (
    JSON.stringify(expected) === JSON.stringify(v['blockers']) &&
    v['canEdit'] === !expected.length &&
    v['canChangeMembership'] === (!expected.length && s.status === 'DRAFT') &&
    (v['memberTotal'] > 200 ||
      (members.length === v['memberTotal'] &&
        members.every((id, i) => id === records[i].id))) &&
    v['members'].every(
      a =>
        !a.canRemove ||
        (s.status === 'DRAFT' && a.status === 'DRAFT' && knownAgent(a)),
    )
  );
}
export function validSettingsCandidates(
  v: unknown,
  org: string,
  team: string,
  page: number,
  current: string[] = [],
): v is SettingsCandidates {
  return (
    integer(page, 0, 100000) &&
    exact(v, 'organizationId teamId checkedAt items page size total') &&
    v['organizationId'] === org &&
    v['teamId'] === team &&
    timestamp(v['checkedAt']) &&
    v['page'] === page &&
    v['size'] === 20 &&
    integer(v['total'], 0, Number.MAX_SAFE_INTEGER) &&
    Array.isArray(v['items']) &&
    v['items'].length === Math.min(20, Math.max(0, v['total'] - page * 20)) &&
    v['items'].every(
      a =>
        agent(a, false) &&
        knownAgent(a) &&
        ['DRAFT', 'ACTIVE'].includes(a.status) &&
        !current.includes(a.id),
    ) &&
    new Set(v['items'].map(a => a.id)).size === v['items'].length
  );
}
export function validSettingsBody(v: unknown): v is SettingsBody {
  return (
    exact(
      v,
      'expectedRevision expectedFingerprint name purpose description managerAgentId agentIds intake concurrency monthlyBudget acknowledgement',
    ) &&
    integer(v['expectedRevision']) &&
    fingerprint(v['expectedFingerprint']) &&
    safeText(v['name'], 255, true) &&
    strip(v['name']) === v['name'] &&
    safeText(v['purpose'], 2000, true) &&
    strip(v['purpose']) === v['purpose'] &&
    (v['description'] === null || safeText(v['description'], 4000)) &&
    (v['managerAgentId'] === null || settingsUuid(v['managerAgentId'])) &&
    ids(v['agentIds'], true) &&
    !v['agentIds'].includes(v['managerAgentId'] as string) &&
    v['agentIds'].length + (v['managerAgentId'] ? 1 : 0) <= 200 &&
    v['intake'] === 'MANUAL' &&
    integer(v['concurrency'], 1, 1000) &&
    budget(v['monthlyBudget']) &&
    v['acknowledgement'] === true &&
    new TextEncoder().encode(JSON.stringify(v)).length <= 32768
  );
}
export function desiredState(
  b: SettingsBody,
  status: SettingsState['status'],
): SettingsState {
  return {
    name: b.name,
    purpose: b.purpose,
    description: b.description,
    status,
    managerAgentId: b.managerAgentId,
    agentIds: [...b.agentIds],
    intake: b.intake,
    concurrency: b.concurrency,
    monthlyBudget: b.monthlyBudget,
    currency: 'USD',
  };
}
export function sameState(a: SettingsState, b: SettingsState): boolean {
  return stateKeys
    .split(' ')
    .every(
      k =>
        JSON.stringify(a[k as keyof SettingsState]) ===
        JSON.stringify(b[k as keyof SettingsState]),
    );
}
export function validSettingsReceipt(
  v: unknown,
  p: SettingsLocator,
  body?: SettingsBody,
  before?: SettingsState,
): v is SettingsReceipt {
  if (
    !exact(
      v,
      'operation organizationId teamId idempotencyKey revision eventId actorId actorName recordedAt before after',
    ) ||
    ![p.org, p.team, p.actor, p.key].every(settingsUuid) ||
    v['operation'] !== 'UPDATE_TEAM_SETTINGS' ||
    v['organizationId'] !== p.org ||
    v['teamId'] !== p.team ||
    v['actorId'] !== p.actor ||
    v['idempotencyKey'] !== p.key ||
    !integer(v['revision'], 1) ||
    !settingsUuid(v['eventId']) ||
    !safeText(v['actorName'], 255, true) ||
    !timestamp(v['recordedAt']) ||
    !validSettingsState(v['before']) ||
    !validSettingsState(v['after'], true)
  )
    return false;
  return (
    v['before'].status !== 'ARCHIVED' &&
    v['before'].status === v['after'].status &&
    v['after'].agentIds.length + (v['after'].managerAgentId ? 1 : 0) <= 200 &&
    strip(v['after'].name) === v['after'].name &&
    strip(v['after'].purpose!) === v['after'].purpose &&
    (v['before'].status !== 'ACTIVE' ||
      (v['before'].managerAgentId === v['after'].managerAgentId &&
        JSON.stringify(v['before'].agentIds) ===
          JSON.stringify(v['after'].agentIds))) &&
    !sameState(v['before'], v['after']) &&
    (!body ||
      (v['revision'] === body.expectedRevision + 1 &&
        sameState(v['after'], desiredState(body, v['before'].status)))) &&
    (!before || sameState(before, v['before']))
  );
}

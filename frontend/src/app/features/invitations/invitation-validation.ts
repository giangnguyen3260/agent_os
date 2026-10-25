export const statuses = [
  'ALL',
  'PENDING',
  'ACCEPTED',
  'REVOKED',
  'EXPIRED',
] as const;
export type Status = (typeof statuses)[number];
export type Operation =
  | 'CREATE_INVITATION'
  | 'RESEND_INVITATION'
  | 'REVOKE_INVITATION';
export interface Invitation {
  id: string;
  email: string;
  role: 'MEMBER';
  status: Exclude<Status, 'ALL'>;
  revision: number;
  createdAt: string;
  expiresAt: string;
  acceptedAt: string | null;
  revokedAt: string | null;
  deliveryStatus: 'QUEUED' | 'SENT' | 'FAILED' | 'CANCELLED';
  lastRequestedAt: string;
}
export interface InvitationPage {
  organizationId: string;
  checkedAt: string;
  items: Invitation[];
  page: number;
  size: number;
  total: number;
}
export interface InvitationContext {
  organizationId: string;
  checkedAt: string;
  invitation: Invitation;
  canResend: boolean;
  canRevoke: boolean;
}
export interface Receipt {
  operation: Operation;
  organizationId: string;
  invitationId: string;
  idempotencyKey: string;
  revision: number;
  eventId: string;
  actorId: string;
  actorName: string;
  recordedAt: string;
  email: string;
  role: 'MEMBER';
  expiresAt: string;
}
export interface Inspection {
  state: 'NEW_ACCOUNT' | 'SIGN_IN' | 'MISMATCH' | 'READY';
  organizationId: string;
  organizationName: string;
  emailHint: string;
  role: 'MEMBER';
  expiresAt: string;
}
export interface Acceptance {
  organizationId: string;
  role: 'ADMIN' | 'MEMBER';
  requiresLogin: boolean;
  alreadyMember: boolean;
}
export const record = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
export const exact = (
  v: unknown,
  names: string,
): v is Record<string, unknown> =>
  record(v) &&
  Object.keys(v).sort().join(' ') === names.split(' ').sort().join(' ');
export const uuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
    v,
  );
export const strip = (v: string): string =>
  v.replace(
    // eslint-disable-next-line no-control-regex -- Java String.strip whitespace.
    /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g,
    '',
  );
export const normalizeEmail = (v: string): string => strip(v).toLowerCase();
export function email(v: unknown): v is string {
  if (typeof v !== 'string' || v.length > 254 || v !== normalizeEmail(v))
    return false;
  const parts = v.split('@');
  if (parts.length !== 2) return false;
  const [local, domain] = parts;
  return (
    local.length >= 1 &&
    local.length <= 64 &&
    /^[a-z0-9!#$%&'*+/=?^_`{|}~.-]+$/.test(local) &&
    !local.startsWith('.') &&
    !local.endsWith('.') &&
    !local.includes('..') &&
    domain.includes('.') &&
    domain
      .split('.')
      .every(
        label =>
          label.length >= 1 &&
          label.length <= 63 &&
          /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label),
      )
  );
}
const unicode = (v: string): boolean =>
  !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(
    v,
  );
export const displayName = (v: unknown): v is string =>
  typeof v === 'string' &&
  unicode(v) &&
  v === strip(v) &&
  [...v].length >= 1 &&
  [...v].length <= 255 &&
  // eslint-disable-next-line no-control-regex -- Reject C0 and DEL.
  !/[\x00-\x1f\x7f]/.test(v);
export const password = (v: unknown): v is string =>
  typeof v === 'string' &&
  unicode(v) &&
  [...v].length >= 14 &&
  new TextEncoder().encode(v).length <= 72;
export const integer = (v: unknown, min = 1, max = 2147483647): v is number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max;
export function instant(v: unknown): bigint | null {
  if (typeof v !== 'string') return null;
  const m = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?Z$/.exec(v);
  if (!m) return null;
  const ms = Date.parse(m[1] + 'Z');
  return Number.isFinite(ms) && new Date(ms).toISOString().slice(0, 19) === m[1]
    ? BigInt(ms) * 1000n + BigInt((m[2] || '').padEnd(6, '0'))
    : null;
}
const date = (v: unknown): v is string => instant(v) !== null;
export function validInvitation(
  v: unknown,
  checkedAt: string,
): v is Invitation {
  if (
    !exact(
      v,
      'id email role status revision createdAt expiresAt acceptedAt revokedAt deliveryStatus lastRequestedAt',
    ) ||
    !uuid(v['id']) ||
    !email(v['email']) ||
    v['role'] !== 'MEMBER' ||
    !integer(v['revision']) ||
    !date(checkedAt) ||
    !date(v['createdAt']) ||
    !date(v['expiresAt']) ||
    !date(v['lastRequestedAt']) ||
    !['QUEUED', 'SENT', 'FAILED', 'CANCELLED'].includes(
      String(v['deliveryStatus']),
    )
  )
    return false;
  const created = instant(v['createdAt'])!;
  const expiry = instant(v['expiresAt'])!;
  const checked = instant(checkedAt)!;
  if (
    expiry !== created + 72n * 3600n * 1000000n ||
    instant(v['lastRequestedAt'])! > checked ||
    created > checked
  )
    return false;
  if (
    v['acceptedAt'] !== null &&
    (!date(v['acceptedAt']) ||
      instant(v['acceptedAt'])! < created ||
      instant(v['acceptedAt'])! >= expiry ||
      instant(v['acceptedAt'])! > checked)
  )
    return false;
  if (
    v['revokedAt'] !== null &&
    (!date(v['revokedAt']) ||
      instant(v['revokedAt'])! < created ||
      instant(v['revokedAt'])! >= expiry ||
      instant(v['revokedAt'])! > checked)
  )
    return false;
  if (
    (v['acceptedAt'] !== null && v['revokedAt'] !== null) ||
    ((v['acceptedAt'] !== null || v['revokedAt'] !== null) && v['revision'] < 2)
  )
    return false;
  return (
    v['status'] ===
    (v['acceptedAt'] !== null
      ? 'ACCEPTED'
      : v['revokedAt'] !== null
        ? 'REVOKED'
        : expiry <= checked
          ? 'EXPIRED'
          : 'PENDING')
  );
}
export function validPage(
  v: unknown,
  org: string,
  page: number,
  status: Status,
): v is InvitationPage {
  if (
    !exact(v, 'organizationId checkedAt items page size total') ||
    !uuid(org) ||
    !integer(page, 0, 100000) ||
    !statuses.includes(status) ||
    v['organizationId'] !== org ||
    !date(v['checkedAt']) ||
    v['page'] !== page ||
    v['size'] !== 20 ||
    !integer(v['total'], 0, Number.MAX_SAFE_INTEGER) ||
    !Array.isArray(v['items']) ||
    v['items'].length !== Math.min(20, Math.max(0, v['total'] - page * 20))
  )
    return false;
  const items = v['items'];
  return (
    items.every(
      i =>
        validInvitation(i, v['checkedAt'] as string) &&
        (status === 'ALL' || i.status === status),
    ) &&
    new Set(items.map(i => i.id)).size === items.length &&
    items.every(
      (i, index) =>
        index === 0 ||
        instant(items[index - 1].createdAt)! > instant(i.createdAt)! ||
        (items[index - 1].createdAt === i.createdAt &&
          items[index - 1].id > i.id),
    )
  );
}
export function validContext(
  v: unknown,
  org: string,
  id: string,
): v is InvitationContext {
  if (
    !exact(v, 'organizationId checkedAt invitation canResend canRevoke') ||
    !uuid(org) ||
    !uuid(id) ||
    v['organizationId'] !== org ||
    !date(v['checkedAt']) ||
    !validInvitation(v['invitation'], v['checkedAt']) ||
    v['invitation'].id !== id
  )
    return false;
  const pending = v['invitation'].status === 'PENDING';
  return (
    v['canRevoke'] === pending &&
    v['canResend'] ===
      (pending &&
        instant(v['checkedAt'])! - instant(v['invitation'].lastRequestedAt)! >=
          60000000n)
  );
}
export interface Correlation {
  organizationId: string;
  actorId: string;
  key: string;
  operation: Operation;
  invitationId?: string;
  revision?: number;
  email?: string;
}
export function validReceipt(v: unknown, c: Correlation): v is Receipt {
  return (
    exact(
      v,
      'operation organizationId invitationId idempotencyKey revision eventId actorId actorName recordedAt email role expiresAt',
    ) &&
    v['operation'] === c.operation &&
    v['organizationId'] === c.organizationId &&
    v['actorId'] === c.actorId &&
    v['idempotencyKey'] === c.key &&
    uuid(v['organizationId']) &&
    uuid(v['actorId']) &&
    uuid(v['idempotencyKey']) &&
    uuid(v['invitationId']) &&
    (!c.invitationId || v['invitationId'] === c.invitationId) &&
    uuid(v['eventId']) &&
    integer(v['revision']) &&
    (c.operation !== 'CREATE_INVITATION' || v['revision'] === 1) &&
    (c.revision === undefined || v['revision'] === c.revision + 1) &&
    displayName(v['actorName']) &&
    email(v['email']) &&
    (!c.email || v['email'] === c.email) &&
    v['role'] === 'MEMBER' &&
    date(v['recordedAt']) &&
    date(v['expiresAt']) &&
    instant(v['recordedAt'])! < instant(v['expiresAt'])! &&
    (c.operation === 'CREATE_INVITATION'
      ? instant(v['expiresAt'])! - instant(v['recordedAt'])! === 259200000000n
      : v['revision'] >= 2)
  );
}
export function validInspection(v: unknown): v is Inspection {
  return (
    exact(
      v,
      'state organizationId organizationName emailHint role expiresAt',
    ) &&
    ['NEW_ACCOUNT', 'SIGN_IN', 'MISMATCH', 'READY'].includes(
      String(v['state']),
    ) &&
    uuid(v['organizationId']) &&
    displayName(v['organizationName']) &&
    typeof v['emailHint'] === 'string' &&
    v['emailHint'].length > 0 &&
    v['emailHint'].length <= 257 &&
    /^[a-z0-9!#$%&'*+/=?^_`{|}~-]\*{3}@/.test(v['emailHint']) &&
    email('a@' + v['emailHint'].split('@')[1]) &&
    v['emailHint'].split('@').length === 2 &&
    v['role'] === 'MEMBER' &&
    date(v['expiresAt'])
  );
}
export function validAcceptance(
  v: unknown,
  org: string,
  fresh: boolean,
): v is Acceptance {
  return (
    exact(v, 'organizationId role requiresLogin alreadyMember') &&
    uuid(org) &&
    v['organizationId'] === org &&
    ['ADMIN', 'MEMBER'].includes(String(v['role'])) &&
    v['requiresLogin'] === fresh &&
    typeof v['alreadyMember'] === 'boolean' &&
    (!fresh || (v['role'] === 'MEMBER' && v['alreadyMember'] === false))
  );
}

export function envelope(
  v: unknown,
): v is {data: unknown; meta: {requestId: string}} {
  return (
    exact(v, 'data meta') &&
    exact(v['meta'], 'requestId') &&
    uuid(v['meta']['requestId'])
  );
}

/** Canonical domain fingerprint input; never persisted in browser storage. */
export function canonicalCommand(c: Correlation, body: unknown): string {
  const create = c.operation === 'CREATE_INVITATION';
  if (
    !uuid(c.organizationId) ||
    !uuid(c.actorId) ||
    !uuid(c.key) ||
    !['CREATE_INVITATION', 'RESEND_INVITATION', 'REVOKE_INVITATION'].includes(
      c.operation,
    ) ||
    !exact(
      body,
      create ? 'email acknowledgement' : 'expectedRevision acknowledgement',
    ) ||
    body['acknowledgement'] !== true ||
    (create
      ? !email(body['email'])
      : !uuid(c.invitationId) || !integer(body['expectedRevision']))
  )
    throw new Error('Invalid invitation command');
  return JSON.stringify({
    acknowledgement: true,
    actorId: c.actorId,
    domain: 'MEMBER_INVITATIONS',
    email: create ? body['email'] : null,
    expectedRevision: create ? null : body['expectedRevision'],
    invitationId: create ? null : c.invitationId,
    operation: c.operation,
    organizationId: c.organizationId,
    version: 1,
  });
}

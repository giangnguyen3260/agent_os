import {
  exact,
  timestamp,
  uuid,
} from '../task-dependencies/task-dependencies-validation';
import {providerMode, validName, validWorkspace} from './provider-create-wire';
import {Provider, ProviderConnectionItem} from './provider-list-wire';

export interface ReviewedRename {
  connectionId: string;
  expectedRevision: string;
  name: string;
}

export interface ReviewedDisable {
  connectionId: string;
  expectedRevision: string;
  /** The reviewed connection name must remain unchanged. */
  name: string;
}

export interface ReviewedEnable {
  connectionId: string;
  expectedRevision: string;
  /** The reviewed connection name must remain unchanged. */
  name: string;
}

export interface ReviewedRotate extends ReviewedEnable {
  expectedCredentialRevision: string;
  state: ProviderConnectionItem['state'];
}

const operationLabels = {
  RENAME: 'Rename',
  DISABLE: 'Disable',
  ENABLE: 'Enable',
  ROTATE: 'Rotate',
  ACCESS_CHECK: 'Access Check',
} as const;
type MetadataOperation = keyof typeof operationLabels;
type ReviewedCommand = ReviewedEnable | ReviewedRotate;

interface HistoricalSnapshot {
  organizationId: string;
  id: string;
  provider: Provider;
  credentialMode: string;
  name: string;
  options: {workspaceId?: string | null};
  state: 'ENABLED' | 'DISABLED';
  revision: string;
  credentialRevision: string;
  createdAt: string;
  updatedAt: string;
}

const maximumRevision = 9223372036854775807n;

function positiveRevision(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[1-9][0-9]{0,18}$/.test(value) &&
    BigInt(value) <= maximumRevision
  );
}

function wireTime(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    !value.startsWith('0000') &&
    /\.\d{6}Z$/.test(value) &&
    timestamp(value)
  );
}

function validSnapshot(
  value: unknown,
  org: string,
  target: string,
): value is HistoricalSnapshot {
  if (
    !exact(
      value,
      'organizationId id provider credentialMode name options state revision credentialRevision createdAt updatedAt',
    ) ||
    value['organizationId'] !== org ||
    value['id'] !== target ||
    (value['provider'] !== 'OPENAI' &&
      value['provider'] !== 'ANTHROPIC' &&
      value['provider'] !== 'GOOGLE_GEMINI') ||
    value['credentialMode'] !== providerMode(value['provider']) ||
    !validName(value['name']) ||
    (value['state'] !== 'ENABLED' && value['state'] !== 'DISABLED') ||
    !positiveRevision(value['revision']) ||
    !positiveRevision(value['credentialRevision']) ||
    BigInt(value['credentialRevision']) > BigInt(value['revision']) ||
    !wireTime(value['createdAt']) ||
    !wireTime(value['updatedAt'])
  )
    return false;
  const options = value['options'];
  if (value['provider'] === 'ANTHROPIC') {
    return (
      exact(options, 'workspaceId') &&
      (options['workspaceId'] === null ||
        validWorkspace(options['workspaceId']))
    );
  }
  return (
    options !== null &&
    typeof options === 'object' &&
    !Array.isArray(options) &&
    Reflect.ownKeys(options).length === 0
  );
}

function receiptMatches(
  value: unknown,
  org: string,
  actor: string,
  submitted: ReviewedCommand,
  operation: MetadataOperation,
): boolean {
  if (
    !uuid(org) ||
    !uuid(actor) ||
    !uuid(submitted.connectionId) ||
    !positiveRevision(submitted.expectedRevision) ||
    !validName(submitted.name) ||
    !exact(
      value,
      'organizationId commandId operation connectionId actorId committedAt before after checkId',
    ) ||
    value['organizationId'] !== org ||
    value['actorId'] !== actor ||
    value['connectionId'] !== submitted.connectionId ||
    !uuid(value['commandId']) ||
    value['operation'] !== operation ||
    (operation === 'ACCESS_CHECK'
      ? !uuid(value['checkId'])
      : value['checkId'] !== null) ||
    !wireTime(value['committedAt']) ||
    !validSnapshot(value['before'], org, submitted.connectionId) ||
    !validSnapshot(value['after'], org, submitted.connectionId)
  )
    return false;
  const before = value['before'];
  const after = value['after'];
  if (operation === 'ACCESS_CHECK') {
    return (
      'expectedCredentialRevision' in submitted &&
      before.revision === submitted.expectedRevision &&
      before.credentialRevision === submitted.expectedCredentialRevision &&
      before.name === submitted.name &&
      before.state === 'ENABLED' &&
      before.createdAt <= before.updatedAt &&
      before.updatedAt <= value['committedAt'] &&
      before.provider === after.provider &&
      before.credentialMode === after.credentialMode &&
      before.state === after.state &&
      before.name === after.name &&
      before.revision === after.revision &&
      before.credentialRevision === after.credentialRevision &&
      before.options.workspaceId === after.options.workspaceId &&
      before.createdAt === after.createdAt &&
      before.updatedAt === after.updatedAt
    );
  }
  return (
    before.revision === submitted.expectedRevision &&
    BigInt(before.revision) < maximumRevision &&
    BigInt(after.revision) === BigInt(before.revision) + 1n &&
    after.name === submitted.name &&
    before.provider === after.provider &&
    before.credentialMode === after.credentialMode &&
    (before.options.workspaceId ?? null) ===
      (after.options.workspaceId ?? null) &&
    (operation === 'ROTATE'
      ? 'expectedCredentialRevision' in submitted &&
        before.credentialRevision === submitted.expectedCredentialRevision &&
        BigInt(after.credentialRevision) ===
          BigInt(before.credentialRevision) + 1n &&
        before.state === submitted.state
      : before.credentialRevision === after.credentialRevision) &&
    before.createdAt === after.createdAt &&
    before.createdAt <= before.updatedAt &&
    before.updatedAt <= after.updatedAt &&
    after.updatedAt <= value['committedAt'] &&
    (operation === 'RENAME'
      ? before.state === after.state && before.name !== after.name
      : operation === 'ROTATE'
        ? before.state === after.state && before.name === after.name
        : before.state !== after.state &&
          after.state === (operation === 'ENABLE' ? 'ENABLED' : 'DISABLED') &&
          before.name === after.name)
  );
}

function data(value: unknown, label: string): unknown {
  if (
    !exact(value, 'data meta') ||
    !exact(value['meta'], 'requestId') ||
    !uuid(value['meta']['requestId'])
  )
    throw new Error(`Invalid ${label} response`);
  return value['data'];
}

/** Only a receipt matching the reviewed metadata command confirms this Rename. */
export function verifyRenamed(
  value: unknown,
  org: string,
  actor: string,
  submitted: ReviewedRename,
): void {
  if (!receiptMatches(data(value, 'Rename'), org, actor, submitted, 'RENAME'))
    throw new Error('Invalid Rename receipt');
}

/** A Disable receipt confirms the reviewed connection and its unchanged name. */
export function verifyDisabled(
  value: unknown,
  org: string,
  actor: string,
  submitted: ReviewedDisable,
): void {
  if (!receiptMatches(data(value, 'Disable'), org, actor, submitted, 'DISABLE'))
    throw new Error('Invalid Disable receipt');
}

/** An Enable receipt confirms the reviewed connection and its unchanged name. */
export function verifyEnabled(
  value: unknown,
  org: string,
  actor: string,
  submitted: ReviewedEnable,
): void {
  if (!receiptMatches(data(value, 'Enable'), org, actor, submitted, 'ENABLE'))
    throw new Error('Invalid Enable receipt');
}

function receiptFound(
  value: unknown,
  org: string,
  actor: string,
  target: string,
  expectedCommand: ReviewedCommand,
  operation: MetadataOperation,
): boolean {
  const label = operationLabels[operation];
  const lookup = data(value, label);
  if (
    !uuid(org) ||
    !uuid(actor) ||
    !uuid(target) ||
    target !== expectedCommand.connectionId ||
    !exact(lookup, 'receipt')
  )
    throw new Error(`Invalid ${label} receipt lookup`);
  if (lookup['receipt'] === null) return false;
  if (
    !receiptMatches(lookup['receipt'], org, actor, expectedCommand, operation)
  )
    throw new Error(`Invalid ${label} receipt`);
  return true;
}

export function receiptFoundRename(
  value: unknown,
  org: string,
  actor: string,
  target: string,
  expectedCommand: ReviewedRename,
): boolean {
  return receiptFound(value, org, actor, target, expectedCommand, 'RENAME');
}

export function receiptFoundDisable(
  value: unknown,
  org: string,
  actor: string,
  target: string,
  expectedCommand: ReviewedDisable,
): boolean {
  return receiptFound(value, org, actor, target, expectedCommand, 'DISABLE');
}

export function receiptFoundEnable(
  value: unknown,
  org: string,
  actor: string,
  target: string,
  expectedCommand: ReviewedEnable,
): boolean {
  return receiptFound(value, org, actor, target, expectedCommand, 'ENABLE');
}

export function verifyRotated(
  value: unknown,
  org: string,
  actor: string,
  submitted: ReviewedCommand,
): void {
  if (!receiptMatches(data(value, 'Rotate'), org, actor, submitted, 'ROTATE'))
    throw new Error('Invalid Rotate receipt');
}

export function receiptFoundRotate(
  value: unknown,
  org: string,
  actor: string,
  target: string,
  expectedCommand: ReviewedCommand,
): boolean {
  return receiptFound(value, org, actor, target, expectedCommand, 'ROTATE');
}

export function accessCheckId(
  value: unknown,
  org: string,
  actor: string,
  review: ReviewedRotate,
  lookup = false,
): string | null {
  let receipt = data(value, 'Access Check');
  if (lookup) {
    if (!exact(receipt, 'receipt'))
      throw new Error('Invalid Access Check lookup');
    receipt = receipt['receipt'];
    if (receipt === null) return null;
  }
  if (!receiptMatches(receipt, org, actor, review, 'ACCESS_CHECK'))
    throw new Error('Invalid Access Check receipt');
  return (receipt as {checkId: string}).checkId;
}

const outcomes = {
  ACCESSIBLE: 'Kết nối có quyền đọc danh sách model.',
  DENIED: 'Nhà cung cấp từ chối quyền truy cập.',
  FAILED: 'Kiểm tra kết nối thất bại.',
  UNKNOWN: 'Chưa xác định được quyền truy cập.',
  SUPERSEDED: 'Kết nối hoặc quyền truy cập đã thay đổi trong lúc kiểm tra.',
} as const;
export function accessCheckStatus(
  value: unknown,
  org: string,
  id: string,
  review: ReviewedRotate,
): {message: string; done: boolean} {
  const result = data(value, 'Access Check');
  if (
    !exact(
      result,
      'organizationId connectionId id kind connectionRevision credentialRevision policyRevision acceptedAt startedAt completedAt expiresAt state outcome errorCode catalogueState modelCount',
    ) ||
    result['organizationId'] !== org ||
    result['connectionId'] !== review.connectionId ||
    result['id'] !== id ||
    result['kind'] !== 'ACCESS_CHECK' ||
    result['connectionRevision'] !== review.expectedRevision ||
    result['credentialRevision'] !== review.expectedCredentialRevision ||
    !positiveRevision(result['policyRevision']) ||
    !wireTime(result['acceptedAt']) ||
    result['catalogueState'] !== 'NOT_REQUESTED' ||
    result['modelCount'] !== null
  )
    throw new Error('Invalid Access Check status');
  const state = result['state'];
  const outcome = result['outcome'];
  if (
    state === 'COMPLETED' &&
    typeof outcome === 'string' &&
    Object.hasOwn(outcomes, outcome) &&
    wireTime(result['completedAt']) &&
    wireTime(result['expiresAt']) &&
    result['acceptedAt'] <= result['completedAt'] &&
    result['completedAt'] <= result['expiresAt'] &&
    (result['startedAt'] === null ||
      (wireTime(result['startedAt']) &&
        result['acceptedAt'] <= result['startedAt'] &&
        result['startedAt'] <= result['completedAt'])) &&
    (outcome === 'ACCESSIBLE'
      ? result['errorCode'] === null && result['startedAt'] !== null
      : typeof result['errorCode'] === 'string' &&
        /^[A-Z_]{1,32}$/.test(result['errorCode']))
  )
    return {message: outcomes[outcome as keyof typeof outcomes], done: true};
  if (
    (state === 'QUEUED' || state === 'RUNNING') &&
    (state === 'QUEUED'
      ? result['startedAt'] === null
      : wireTime(result['startedAt']) &&
        result['acceptedAt'] <= result['startedAt']) &&
    outcome === null &&
    result['errorCode'] === null &&
    result['completedAt'] === null &&
    result['expiresAt'] === null
  )
    return {
      message:
        state === 'QUEUED' ? 'Đang chờ kiểm tra.' : 'Đang kiểm tra kết nối.',
      done: false,
    };
  throw new Error('Invalid Access Check status');
}

import {
  exact,
  timestamp,
  uuid,
} from '../task-dependencies/task-dependencies-validation';
import {providerMode, validName, validWorkspace} from './provider-create-wire';
import {Provider} from './provider-list-wire';

export interface ReviewedRename {
  connectionId: string;
  expectedRevision: string;
  name: string;
}

interface RenameSnapshot {
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

interface RenameReceipt {
  before: RenameSnapshot;
  after: RenameSnapshot;
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
): value is RenameSnapshot {
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
  submitted: ReviewedRename,
): value is RenameReceipt {
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
    value['operation'] !== 'RENAME' ||
    value['checkId'] !== null ||
    !wireTime(value['committedAt']) ||
    !validSnapshot(value['before'], org, submitted.connectionId) ||
    !validSnapshot(value['after'], org, submitted.connectionId)
  )
    return false;
  const before = value['before'];
  const after = value['after'];
  return (
    before.revision === submitted.expectedRevision &&
    BigInt(before.revision) < maximumRevision &&
    BigInt(after.revision) === BigInt(before.revision) + 1n &&
    after.name === submitted.name &&
    before.name !== after.name &&
    before.provider === after.provider &&
    before.credentialMode === after.credentialMode &&
    (before.options.workspaceId ?? null) ===
      (after.options.workspaceId ?? null) &&
    before.state === after.state &&
    before.credentialRevision === after.credentialRevision &&
    before.createdAt === after.createdAt &&
    before.createdAt <= before.updatedAt &&
    before.updatedAt <= after.updatedAt &&
    after.updatedAt <= value['committedAt']
  );
}

function data(value: unknown): unknown {
  if (
    !exact(value, 'data meta') ||
    !exact(value['meta'], 'requestId') ||
    !uuid(value['meta']['requestId'])
  )
    throw new Error('Invalid Rename response');
  return value['data'];
}

/** Only a receipt matching the reviewed metadata command confirms this Rename. */
export function verifyRenamed(
  value: unknown,
  org: string,
  actor: string,
  submitted: ReviewedRename,
): void {
  if (!receiptMatches(data(value), org, actor, submitted))
    throw new Error('Invalid Rename receipt');
}

export function receiptFoundRename(
  value: unknown,
  org: string,
  actor: string,
  target: string,
  expectedCommand: ReviewedRename,
): boolean {
  const lookup = data(value);
  if (
    !uuid(org) ||
    !uuid(actor) ||
    !uuid(target) ||
    target !== expectedCommand.connectionId ||
    !exact(lookup, 'receipt')
  )
    throw new Error('Invalid Rename receipt lookup');
  if (lookup['receipt'] === null) return false;
  if (!receiptMatches(lookup['receipt'], org, actor, expectedCommand))
    throw new Error('Invalid Rename receipt');
  return true;
}

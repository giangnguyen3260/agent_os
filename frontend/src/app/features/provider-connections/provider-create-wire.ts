/* eslint-disable no-control-regex -- Mirrors the server's whitespace normalization. */
import {
  exact,
  text,
  timestamp,
  uuid,
} from '../task-dependencies/task-dependencies-validation';
import {Provider} from './provider-list-wire';

const profiles = {
  OPENAI: {mode: 'OPENAI_API_KEY', schema: 'EMPTY'},
  ANTHROPIC: {mode: 'ANTHROPIC_API_KEY', schema: 'ANTHROPIC_WORKSPACE'},
  GOOGLE_GEMINI: {mode: 'GEMINI_API_KEY', schema: 'EMPTY'},
} as const;

export const providerMode = (provider: Provider) => profiles[provider].mode;
export const validWorkspace = (value: unknown): value is string =>
  typeof value === 'string' && /^wrkspc_[A-Za-z0-9]{1,128}$/.test(value);
export const normalizedName = (value: string): string =>
  value.replace(
    /^[\u0009-\u000d\u001c-\u001f\p{Z}\uFEFF]+|[\u0009-\u000d\u001c-\u001f\p{Z}\uFEFF]+$/gu,
    '',
  );
export const validName = (value: unknown): value is string =>
  text(value, 100) &&
  !!value &&
  normalizedName(value) === value &&
  !/[\u2028\u2029]/.test(value);

function isProvider(value: unknown): value is Provider {
  return typeof value === 'string' && Object.hasOwn(profiles, value);
}

function data(value: unknown): unknown {
  if (
    !exact(value, 'data meta') ||
    !exact(value['meta'], 'requestId') ||
    !uuid(value['meta']['requestId'])
  )
    throw new Error('Invalid provider response');
  return value['data'];
}

export function enabledProviders(value: unknown, org: string): Provider[] {
  const options = data(value);
  if (
    !exact(options, 'organizationId policyRevision providers') ||
    options['organizationId'] !== org ||
    typeof options['policyRevision'] !== 'string' ||
    !/^[1-9][0-9]{0,18}$/.test(options['policyRevision']) ||
    BigInt(options['policyRevision']) > 9223372036854775807n ||
    !Array.isArray(options['providers']) ||
    options['providers'].length !== 3
  )
    throw new Error('Invalid provider options');
  const enabled: Provider[] = [];
  const seen = new Set<Provider>();
  for (const row of options['providers'] as unknown[]) {
    if (!exact(row, 'provider credentialMode enabled optionsSchema')) {
      throw new Error('Invalid provider option');
    }
    const provider = row['provider'];
    if (
      !isProvider(provider) ||
      seen.has(provider) ||
      row['credentialMode'] !== profiles[provider].mode ||
      row['optionsSchema'] !== profiles[provider].schema ||
      typeof row['enabled'] !== 'boolean'
    )
      throw new Error('Invalid provider option');
    seen.add(provider);
    if (row['enabled']) enabled.push(provider);
  }
  return enabled;
}

export function csrfHeader(value: unknown): string {
  const token = data(value);
  if (
    !exact(token, 'headerName token') ||
    token['headerName'] !== 'X-CSRF-TOKEN' ||
    typeof token['token'] !== 'string' ||
    !/^[A-Za-z0-9_+/=-]{1,512}$/.test(token['token'])
  )
    throw new Error('Invalid CSRF token');
  return token['token'];
}

function wireTime(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    !value.startsWith('0000') &&
    /\.\d{6}Z$/.test(value) &&
    timestamp(value)
  );
}

interface CreateSnapshot {
  provider: Provider;
  name: string;
  options: {workspaceId?: string | null};
}

function validReceipt(
  value: unknown,
  org: string,
  actor: string,
): value is {after: CreateSnapshot} {
  if (
    !exact(
      value,
      'organizationId commandId operation connectionId actorId committedAt before after checkId',
    ) ||
    value['organizationId'] !== org ||
    value['actorId'] !== actor ||
    !uuid(value['commandId']) ||
    !uuid(value['connectionId']) ||
    value['operation'] !== 'CREATE' ||
    value['before'] !== null ||
    value['checkId'] !== null ||
    !wireTime(value['committedAt'])
  )
    return false;
  const snapshot = value['after'];
  if (
    !exact(
      snapshot,
      'organizationId id provider credentialMode name options state revision credentialRevision createdAt updatedAt',
    ) ||
    snapshot['organizationId'] !== org ||
    snapshot['id'] !== value['connectionId'] ||
    !isProvider(snapshot['provider']) ||
    snapshot['credentialMode'] !== providerMode(snapshot['provider']) ||
    !validName(snapshot['name']) ||
    snapshot['state'] !== 'ENABLED' ||
    snapshot['revision'] !== '1' ||
    snapshot['credentialRevision'] !== '1' ||
    !wireTime(snapshot['createdAt']) ||
    snapshot['createdAt'] !== snapshot['updatedAt'] ||
    snapshot['createdAt'] > value['committedAt']
  )
    return false;
  const options = snapshot['options'];
  if (snapshot['provider'] === 'ANTHROPIC') {
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

export function verifyCreated(
  value: unknown,
  org: string,
  actor: string,
  submitted: {provider: Provider; name: string; workspaceId: string | null},
): void {
  const receipt = data(value);
  if (
    !validReceipt(receipt, org, actor) ||
    receipt.after.provider !== submitted.provider ||
    receipt.after.name !== submitted.name ||
    (receipt.after.options.workspaceId ?? null) !== submitted.workspaceId
  )
    throw new Error('Invalid Create receipt');
}

export function receiptFound(
  value: unknown,
  org: string,
  actor: string,
): boolean {
  const lookup = data(value);
  if (!exact(lookup, 'receipt')) throw new Error('Invalid receipt lookup');
  if (lookup['receipt'] === null) return false;
  if (!validReceipt(lookup['receipt'], org, actor))
    throw new Error('Invalid Create receipt');
  return true;
}

export function createErrorCode(value: unknown): string | null {
  if (
    !exact(value, 'error meta') ||
    !exact(value['meta'], 'requestId') ||
    !uuid(value['meta']['requestId']) ||
    !exact(value['error'], 'code message details') ||
    typeof value['error']['code'] !== 'string' ||
    typeof value['error']['message'] !== 'string'
  )
    return null;
  const details = value['error']['details'];
  return details &&
    typeof details === 'object' &&
    !Array.isArray(details) &&
    Reflect.ownKeys(details).length === 0
    ? value['error']['code']
    : null;
}

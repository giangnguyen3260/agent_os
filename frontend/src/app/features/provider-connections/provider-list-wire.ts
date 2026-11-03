import {
  exact,
  text,
  timestamp,
  uuid,
} from '../task-dependencies/task-dependencies-validation';

const modes = {
  OPENAI: 'OPENAI_API_KEY',
  ANTHROPIC: 'ANTHROPIC_API_KEY',
  GOOGLE_GEMINI: 'GEMINI_API_KEY',
} as const;

export type Provider = keyof typeof modes;
export interface ProviderConnectionItem {
  organizationId: string;
  id: string;
  provider: Provider;
  credentialMode: string;
  name: string;
  options: {workspaceId?: string | null};
  state: 'ENABLED' | 'DISABLED';
  revision: string;
  credentialRevision: string;
  credentialState: 'PRESENT' | 'UNAVAILABLE';
  credentialMask: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ProviderPage {
  organizationId: string;
  page: number;
  pageSize: 20;
  total: number;
  items: ProviderConnectionItem[];
}

const revision = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[1-9][0-9]{0,18}$/.test(value) &&
  BigInt(value) <= 9223372036854775807n;
const wireTime = (value: unknown) =>
  typeof value === 'string' &&
  !value.startsWith('0000') &&
  /\.\d{6}Z$/.test(value) &&
  timestamp(value);

function item(value: unknown, org: string): value is ProviderConnectionItem {
  if (
    !exact(
      value,
      'organizationId id provider credentialMode name options state revision credentialRevision credentialState credentialMask createdAt updatedAt',
    ) ||
    value['organizationId'] !== org ||
    !uuid(value['id']) ||
    typeof value['provider'] !== 'string' ||
    !Object.hasOwn(modes, value['provider']) ||
    value['credentialMode'] !== modes[value['provider'] as Provider] ||
    !text(value['name'], 100) ||
    !value['name'] ||
    value['name'].trim() !== value['name'] ||
    /[\u2028\u2029]/.test(value['name']) ||
    !['ENABLED', 'DISABLED'].some(state => state === value['state']) ||
    !revision(value['revision']) ||
    !revision(value['credentialRevision']) ||
    BigInt(value['credentialRevision']) > BigInt(value['revision']) ||
    !wireTime(value['createdAt']) ||
    !wireTime(value['updatedAt']) ||
    String(value['updatedAt']) < String(value['createdAt'])
  )
    return false;
  const options = value['options'];
  if (value['provider'] === 'ANTHROPIC') {
    if (
      !exact(options, 'workspaceId') ||
      (options['workspaceId'] !== null &&
        (typeof options['workspaceId'] !== 'string' ||
          !/^wrkspc_[A-Za-z0-9]{1,128}$/.test(options['workspaceId'])))
    )
      return false;
  } else if (!isEmptyOptions(options)) {
    return false;
  }
  return value['credentialState'] === 'PRESENT'
    ? value['credentialMask'] === '••••••••'
    : value['credentialState'] === 'UNAVAILABLE' &&
        value['credentialMask'] === null;
}

function isEmptyOptions(value: unknown): boolean {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Reflect.ownKeys(value).length === 0
  );
}

export function parseProviderPage(
  value: unknown,
  org: string,
  page: number,
): ProviderPage {
  if (
    !exact(value, 'data meta') ||
    !exact(value['meta'], 'requestId') ||
    !uuid(value['meta']['requestId']) ||
    !exact(value['data'], 'organizationId page pageSize total items')
  )
    throw new Error('Invalid provider list');
  const data = value['data'];
  const total = data['total'];
  const items = data['items'];
  if (
    data['organizationId'] !== org ||
    data['page'] !== page ||
    data['pageSize'] !== 20 ||
    typeof total !== 'number' ||
    !Number.isSafeInteger(total) ||
    total < 0 ||
    !Array.isArray(items) ||
    items.length !== Math.min(20, Math.max(0, total - page * 20)) ||
    !items.every(row => item(row, org)) ||
    new Set(items.map(row => row.id)).size !== items.length
  )
    throw new Error('Invalid provider list');
  return {
    organizationId: org,
    page,
    pageSize: 20,
    total,
    items,
  };
}

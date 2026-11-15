import {
  exact,
  revision,
  uuid,
} from '../task-dependencies/task-dependencies-validation';

const states = [
  'ABSENT',
  'GRANTED',
  'REVOKED',
  'STALE',
  'UNAVAILABLE',
] as const;
type GrantState = (typeof states)[number];

export interface ModelGrant {
  revision: number;
  bindingRevision: number;
  state: GrantState;
  canGrant: boolean;
  canRevoke: boolean;
}

export interface ModelGrantRequest {
  expectedRevision: number;
  expectedBindingRevision: number;
  enabled: boolean;
}

export function modelGrant(value: unknown): ModelGrant {
  if (
    !exact(value, 'data meta') ||
    !exact(value['meta'], 'requestId') ||
    !uuid(value['meta']['requestId'])
  )
    throw new Error('Invalid model grant response');
  const data = value['data'];
  if (
    !exact(data, 'revision bindingRevision state canGrant canRevoke') ||
    !revision(data['revision']) ||
    !revision(data['bindingRevision']) ||
    !states.some(state => state === data['state']) ||
    typeof data['canGrant'] !== 'boolean' ||
    typeof data['canRevoke'] !== 'boolean'
  )
    throw new Error('Invalid model grant state');
  return data as unknown as ModelGrant;
}

export function confirmedModelGrant(
  value: unknown,
  request: ModelGrantRequest,
): ModelGrant {
  const grant = modelGrant(value);
  if (
    grant.revision !== request.expectedRevision + 1 ||
    grant.bindingRevision !== request.expectedBindingRevision ||
    grant.state !== (request.enabled ? 'GRANTED' : 'REVOKED')
  )
    throw new Error('Model grant save could not be confirmed');
  return grant;
}

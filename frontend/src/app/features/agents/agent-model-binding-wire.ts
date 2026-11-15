import {validName} from '../provider-connections/provider-create-wire';
import {Provider} from '../provider-connections/provider-list-wire';
import {
  modelText,
  positiveRevision,
} from '../provider-connections/provider-rename-wire';
import {
  exact,
  revision,
  uuid,
} from '../task-dependencies/task-dependencies-validation';

export interface ModelBindingSelection {
  connectionId: string;
  checkId: string;
  modelId: string;
}

export interface ModelBinding extends ModelBindingSelection {
  connectionName: string;
  provider: Provider;
  displayName: string | null;
  status: 'CURRENT' | 'STALE' | 'UNAVAILABLE';
}

export interface ModelBindingState {
  revision: number;
  editable: boolean;
  binding: ModelBinding | null;
}

export interface ModelBindingConnection {
  connectionId: string;
  connectionName: string;
  provider: Provider;
  checkId: string;
  credentialRevision: string;
  modelCount: number;
  catalogueState: 'COMPLETE' | 'TRUNCATED';
}

export interface ModelBindingRequest {
  expectedRevision: number;
  selection: ModelBindingSelection | null;
}

function envelope(value: unknown): unknown {
  if (
    !exact(value, 'data meta') ||
    !exact(value['meta'], 'requestId') ||
    !uuid(value['meta']['requestId'])
  )
    throw new Error('Invalid model binding response');
  return value['data'];
}

function provider(value: unknown): value is Provider {
  return (
    value === 'OPENAI' || value === 'ANTHROPIC' || value === 'GOOGLE_GEMINI'
  );
}

export function bindingState(value: unknown): ModelBindingState {
  const data = envelope(value);
  if (
    !exact(data, 'revision editable binding') ||
    !revision(data['revision']) ||
    typeof data['editable'] !== 'boolean'
  )
    throw new Error('Invalid model binding state');
  const binding = data['binding'];
  if (
    binding !== null &&
    (!exact(
      binding,
      'connectionId connectionName provider checkId modelId displayName status',
    ) ||
      !uuid(binding['connectionId']) ||
      !validName(binding['connectionName']) ||
      !provider(binding['provider']) ||
      !uuid(binding['checkId']) ||
      !modelText(binding['modelId'], 256) ||
      (binding['displayName'] !== null &&
        !modelText(binding['displayName'], 255)) ||
      (binding['status'] !== 'CURRENT' &&
        binding['status'] !== 'STALE' &&
        binding['status'] !== 'UNAVAILABLE'))
  )
    throw new Error('Invalid model binding');
  return data as unknown as ModelBindingState;
}

export function bindingConnections(value: unknown): ModelBindingConnection[] {
  const data = envelope(value);
  if (
    !exact(data, 'items') ||
    !Array.isArray(data['items']) ||
    data['items'].length > 100
  )
    throw new Error('Invalid model binding connections');
  const ids = new Set<string>();
  const items: ModelBindingConnection[] = [];
  for (const item of data['items'] as unknown[]) {
    if (
      !exact(
        item,
        'connectionId connectionName provider checkId credentialRevision modelCount catalogueState',
      ) ||
      !uuid(item['connectionId']) ||
      ids.has(item['connectionId']) ||
      !validName(item['connectionName']) ||
      !provider(item['provider']) ||
      !uuid(item['checkId']) ||
      !positiveRevision(item['credentialRevision']) ||
      !revision(item['modelCount']) ||
      item['modelCount'] > 1000 ||
      (item['catalogueState'] !== 'COMPLETE' &&
        item['catalogueState'] !== 'TRUNCATED')
    )
      throw new Error('Invalid model binding connection');
    ids.add(item['connectionId']);
    items.push(item as unknown as ModelBindingConnection);
  }
  return items;
}

export function sameBinding(
  a: ModelBindingSelection | null,
  b: ModelBindingSelection | null,
): boolean {
  return (
    a === b ||
    (!!a &&
      !!b &&
      a.connectionId === b.connectionId &&
      a.checkId === b.checkId &&
      a.modelId === b.modelId)
  );
}

export function confirmedBinding(
  value: unknown,
  request: ModelBindingRequest,
): ModelBindingState {
  const result = bindingState(value);
  if (
    result.revision !== request.expectedRevision + 1 ||
    !sameBinding(result.binding, request.selection)
  )
    throw new Error('Model binding save could not be confirmed');
  return result;
}

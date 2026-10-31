import {
  exact,
  sameScope,
  Scope,
  text,
  timestamp,
  uuid,
  Task,
  taskSet,
  sameIds,
} from '../task-dependencies/task-dependencies-validation';
export interface HistoryItem {
  eventId: string;
  revision: number;
  committedAt: string;
  actorName: string | null;
  before: Task[];
  after: Task[];
}
export interface HistoryPage extends Scope {
  teamName: string;
  missionTitle: string;
  taskTitle: string;
  page: number;
  pageSize: number;
  total: number;
  items: HistoryItem[];
}
export const safeText = (v: unknown, max: number): v is string =>
  text(v, max) && !/[\u2028\u2029]/.test(v);
export const safeName = (v: unknown, max: number): v is string =>
  safeText(v, max) && v.trim().length > 0;
export function pageNumber(raw: string): number {
  return /^(0|[1-9]\d*)$/.test(raw) && Number(raw) <= 100000 ? Number(raw) : -1;
}
export function validHistory(
  v: unknown,
  scope: Scope,
  page: number,
): v is HistoryPage {
  if (
    !exact(
      v,
      'organizationId teamId missionId taskId teamName missionTitle taskTitle page pageSize total items',
    )
  )
    return false;
  const p = v as unknown as HistoryPage;
  if (
    !sameScope(p, scope) ||
    p.page !== page ||
    page < 0 ||
    pageNumber(String(page)) !== page ||
    p.pageSize !== 20 ||
    !Number.isSafeInteger(p.total) ||
    p.total < 0 ||
    !safeName(p.teamName, 255) ||
    !safeName(p.missionTitle, 255) ||
    !safeText(p.taskTitle, 255) ||
    !Array.isArray(p.items) ||
    p.items.length !== Math.min(20, Math.max(0, p.total - page * 20))
  )
    return false;
  const events = new Set<string>();
  return p.items.every((item, index) => {
    if (
      !exact(item, 'eventId revision committedAt actorName before after') ||
      !uuid(item.eventId) ||
      events.has(item.eventId) ||
      !Number.isInteger(item.revision) ||
      item.revision < 1 ||
      item.revision > 2147483647 ||
      (index > 0 && p.items[index - 1].revision <= item.revision) ||
      !timestamp(item.committedAt) ||
      !(item.actorName === null || safeName(item.actorName, 255)) ||
      !taskSet(item.before, scope.taskId) ||
      !taskSet(item.after, scope.taskId) ||
      sameIds(item.before, item.after)
    )
      return false;
    events.add(item.eventId);
    return true;
  });
}

// Angular ParamMap preserves duplicate keys; reject them before any request.
export function validQuery(q: {
  keys: string[];
  getAll(key: string): string[];
  get(key: string): string | null;
}): boolean {
  return (
    q.keys.every(k => k === 'page' && q.getAll(k).length === 1) &&
    pageNumber(q.get('page') ?? '0') >= 0
  );
}

import {
  exact,
  sameScope,
  sameValues,
  Scope,
  text,
  timestamp,
  uuid,
  values,
  Values,
} from '../task-edit/task-edit-validation';

export interface HistoryItem {
  eventId: string;
  revision: number;
  committedAt: string;
  actorName: string;
  before: Values;
  after: Values;
}
export interface HistoryPage extends Scope {
  page: number;
  pageSize: number;
  total: number;
  items: HistoryItem[];
}
export function pageNumber(raw: string): number {
  return /^(0|[1-9]\d*)$/.test(raw) && Number(raw) <= 100000 ? Number(raw) : -1;
}
function beforeValues(v: unknown): v is Values {
  return (
    values(v) &&
    text(v.title, 255) &&
    (v.description === null || text(v.description, 8000, true)) &&
    (v.acceptanceCriteria === null || text(v.acceptanceCriteria, 4000, true)) &&
    (v.priority === null || text(v.priority, 255))
  );
}
export function validHistory(
  v: unknown,
  scope: Scope,
  page: number,
): v is HistoryPage {
  if (
    !exact(
      v,
      'organizationId teamId missionId taskId page pageSize total items',
    )
  )
    return false;
  const p = v as unknown as HistoryPage;
  if (
    !sameScope(p, scope) ||
    p.page !== page ||
    pageNumber(String(page)) !== page ||
    p.pageSize !== 20 ||
    !Number.isSafeInteger(p.total) ||
    p.total < 0 ||
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
      !text(item.actorName, 255) ||
      !item.actorName.trim() ||
      !beforeValues(item.before) ||
      !values(item.after, true) ||
      sameValues(item.before, item.after)
    )
      return false;
    events.add(item.eventId);
    return true;
  });
}

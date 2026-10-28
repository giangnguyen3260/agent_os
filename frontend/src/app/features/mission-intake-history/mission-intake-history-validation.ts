import {
  Scope,
  Receipt,
  exact,
  sameScope,
  text,
  timestamp,
  validReceipt,
} from '../mission-intake/mission-intake-validation';

// History names must also reject ISO C1 controls, beyond shared intake text.
export function safeHistoryName(value: unknown): value is string {
  return text(value, 255) && !!value.trim() && !/[\u0080-\u009f]/.test(value);
}

export type HistoryItem = Omit<Receipt, keyof Scope> & {
  actorName: string | null;
};
export interface HistoryPage extends Scope {
  teamName: string;
  missionTitle: string;
  page: number;
  pageSize: number;
  total: number;
  items: HistoryItem[];
}
export function pageNumber(raw: string): number {
  return /^(0|[1-9][0-9]*)$/.test(raw) && Number(raw) <= 100000
    ? Number(raw)
    : -1;
}
// Normalize only the fractional part, preserving all nine Instant digits.
function instantKey(value: string): string {
  return (
    value.slice(0, 19) +
    '.' +
    value.slice(19, -1).replace('.', '').padEnd(9, '0')
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
      'organizationId teamId missionId teamName missionTitle page pageSize total items',
    )
  )
    return false;
  const p = v as unknown as HistoryPage;
  if (
    !sameScope(p, scope) ||
    !safeHistoryName(p.teamName) ||
    !safeHistoryName(p.missionTitle) ||
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
      !exact(
        item,
        'eventId versionBefore versionAfter pausedBefore pausedAfter committedAt actorName',
      ) ||
      !validReceipt(
        {
          ...scope,
          eventId: item.eventId,
          versionBefore: item.versionBefore,
          versionAfter: item.versionAfter,
          pausedBefore: item.pausedBefore,
          pausedAfter: item.pausedAfter,
          committedAt: item.committedAt,
        },
        scope,
      ) ||
      !timestamp(item.committedAt) ||
      events.has(item.eventId) ||
      !(item.actorName === null || safeHistoryName(item.actorName))
    )
      return false;
    if (index > 0) {
      const previous = p.items[index - 1];
      const before = instantKey(previous.committedAt),
        current = instantKey(item.committedAt);
      if (
        before < current ||
        (before === current && previous.eventId <= item.eventId)
      )
        return false;
    }
    events.add(item.eventId);
    return true;
  });
}

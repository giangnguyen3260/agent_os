/* eslint-disable no-control-regex -- Wire rejects forbidden control characters. */
export const uuid = (v: unknown): v is string =>
  typeof v === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
    v,
  );
export const exact = (v: unknown, keys: string): v is Record<string, unknown> =>
  !!v &&
  typeof v === 'object' &&
  !Array.isArray(v) &&
  Reflect.ownKeys(v).length === keys.split(' ').length &&
  keys.split(' ').every(k => Object.prototype.hasOwnProperty.call(v, k));
export const strip = (v: string) =>
  v.replace(
    /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g,
    '',
  );
export function text(v: unknown, max: number, multiline = false): v is string {
  return (
    typeof v === 'string' &&
    !(
      multiline
        ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/
        : /[\u0000-\u001f\u007f-\u009f]/
    ).test(v) &&
    !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(
      v,
    ) &&
    [...v].length <= max
  );
}
export const revision = (v: unknown): v is number =>
  typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= 2147483647;
export const fingerprint = (v: unknown) =>
  typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
export function timestamp(v: unknown): boolean {
  if (
    typeof v !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(v)
  )
    return false;
  const base = v.slice(0, 19) + 'Z';
  return (
    Number.isFinite(Date.parse(base)) &&
    new Date(base).toISOString().slice(0, 19) === v.slice(0, 19)
  );
}
export interface Scope {
  organizationId: string;
  teamId: string;
  missionId: string;
  taskId: string;
}

export interface Task {
  id: string;
  title: string;
  status: string;
}
export interface Candidate extends Task {
  fingerprint: string;
}
export interface Body {
  expectedRevision: number;
  expectedFingerprint: string;
  dependencies: {taskId: string; fingerprint: string}[];
}
export interface Context extends Scope {
  checkedAt: string;
  revision: number;
  fingerprint: string;
  teamName: string;
  missionTitle: string;
  taskTitle: string;
  taskStatus: string;
  dependencies: Task[];
  canEdit: boolean;
  blockers: string[];
}
export interface CandidatePage extends Scope {
  page: number;
  pageSize: number;
  total: number;
  query: string;
  items: Candidate[];
}
export interface Receipt extends Scope {
  eventId: string;
  revision: number;
  committedAt: string;
  before: Task[];
  after: Task[];
}
export interface Locator extends Scope {
  actor: string;
  key: string;
}
export const statuses = [
  'DRAFT',
  'READY',
  'RUNNING',
  'WAITING',
  'BLOCKED',
  'IN_REVIEW',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
];
export const blockerLabels: Record<string, string> = {
  TASK_NOT_DRAFT: 'Chỉ có thể sửa task DRAFT.',
  TEAM_ARCHIVED: 'Team đã được lưu trữ.',
  MISSION_NOT_EDITABLE: 'Trạng thái Mission hiện không cho phép sửa.',
  INTAKE_PAUSED: 'Mission đang tạm dừng tiếp nhận task.',
  REVISION_EXHAUSTED: 'Đã đạt giới hạn phiên bản phụ thuộc.',
  UNKNOWN_STATE: 'Có trạng thái chưa được hỗ trợ; không thể sửa an toàn.',
};
export const errorLabels: Record<string, string> = {
  BAD_REQUEST: 'Yêu cầu không hợp lệ. Kiểm tra dữ liệu và thử lại.',
  UNAUTHORIZED: 'Bạn không còn quyền quản trị để sửa phụ thuộc.',
  FORBIDDEN: 'Bạn không còn quyền quản trị để sửa phụ thuộc.',
  NOT_FOUND: 'Không tìm thấy task trong phạm vi hiện tại.',
  TASK_DEPENDENCIES_CONFLICT:
    'Dữ liệu task hoặc đồ thị đã thay đổi. Tải lại và xem lại lựa chọn.',
  TASK_DEPENDENCIES_BLOCKED: 'Hiện không thể sửa phụ thuộc của task này.',
  TASK_DEPENDENCIES_NOT_ELIGIBLE:
    'Task đã chọn không còn phù hợp trong phạm vi này hoặc sẽ tạo chu trình.',
  TASK_DEPENDENCIES_CANDIDATE_CONFLICT:
    'Thông tin task đã chọn đã thay đổi. Chọn lại và xem lại.',
  TASK_DEPENDENCIES_NO_CHANGE: 'Chưa có thay đổi phụ thuộc để lưu.',
  IDEMPOTENCY_CONFLICT:
    'Khóa yêu cầu đã được dùng cho nội dung khác. Kiểm tra biên nhận trước khi tiếp tục.',
  TASK_DEPENDENCIES_UNAVAILABLE:
    'Không thể xác minh dữ liệu phụ thuộc an toàn. Vui lòng thử tải lại.',
};
export const nonblank = (v: unknown, n: number) =>
  text(v, n) && strip(v).length > 0;
export function sameScope(v: Scope, s: Scope) {
  return (['organizationId', 'teamId', 'missionId', 'taskId'] as const).every(
    k => uuid(v[k]) && v[k] === s[k],
  );
}
export function task(v: unknown, known = true): v is Task {
  return (
    exact(v, 'id title status') &&
    uuid(v['id']) &&
    text(v['title'], 255) &&
    nonblank(v['status'], 50) &&
    (!known || statuses.includes(v['status'] as string))
  );
}
export function candidate(v: unknown): v is Candidate {
  if (!exact(v, 'id title status fingerprint')) return false;
  const {fingerprint: f, ...t} = v;
  return task(t) && fingerprint(f);
}
export function taskSet(v: unknown, source: string, known = true): v is Task[] {
  return (
    Array.isArray(v) &&
    v.length <= 20 &&
    v.every(
      (t, i) =>
        task(t, known) && t.id !== source && (i === 0 || v[i - 1].id < t.id),
    )
  );
}
export const sameIds = (a: Task[], b: Task[]) =>
  a.length === b.length && a.every((t, i) => t.id === b[i].id);
export function validBody(v: unknown, source?: string): v is Body {
  return (
    exact(v, 'expectedRevision expectedFingerprint dependencies') &&
    revision(v['expectedRevision']) &&
    fingerprint(v['expectedFingerprint']) &&
    Array.isArray(v['dependencies']) &&
    v['dependencies'].length <= 20 &&
    v['dependencies'].every(
      (t, i, a) =>
        exact(t, 'taskId fingerprint') &&
        uuid(t['taskId']) &&
        t['taskId'] !== source &&
        fingerprint(t['fingerprint']) &&
        (i === 0 || a[i - 1].taskId < t['taskId']),
    )
  );
}
export function validContext(v: unknown, s: Scope): v is Context {
  if (
    !exact(
      v,
      'organizationId teamId missionId taskId checkedAt revision fingerprint teamName missionTitle taskTitle taskStatus dependencies canEdit blockers',
    )
  )
    return false;
  const c = v as unknown as Context,
    bs = Object.keys(blockerLabels);
  return (
    sameScope(c, s) &&
    timestamp(c.checkedAt) &&
    revision(c.revision) &&
    fingerprint(c.fingerprint) &&
    nonblank(c.teamName, 255) &&
    nonblank(c.missionTitle, 255) &&
    text(c.taskTitle, 255) &&
    nonblank(c.taskStatus, 50) &&
    Array.isArray(c.blockers) &&
    c.blockers.every(
      (b, i) =>
        bs.includes(b) &&
        (i === 0 || bs.indexOf(c.blockers[i - 1]) < bs.indexOf(b)),
    ) &&
    typeof c.canEdit === 'boolean' &&
    c.canEdit === (c.blockers.length === 0) &&
    taskSet(c.dependencies, s.taskId, !c.blockers.includes('UNKNOWN_STATE')) &&
    (statuses.includes(c.taskStatus)
      ? c.blockers.includes('TASK_NOT_DRAFT') === (c.taskStatus !== 'DRAFT')
      : c.blockers.includes('UNKNOWN_STATE') &&
        !c.blockers.includes('TASK_NOT_DRAFT')) &&
    (c.revision === 2147483647) === c.blockers.includes('REVISION_EXHAUSTED') &&
    (!c.canEdit || (c.taskStatus === 'DRAFT' && c.revision < 2147483647))
  );
}
// JS UTF-16 comparison is wrong for supplementary characters; compare scalar values.
export function titleOrder(a: string, b: string) {
  const x = Array.from(a),
    y = Array.from(b);
  for (let i = 0; i < Math.min(x.length, y.length); i++) {
    const d = x[i].codePointAt(0)! - y[i].codePointAt(0)!;
    if (d) return d;
  }
  return x.length - y.length;
}
export function validPage(
  v: unknown,
  s: Scope,
  page: number,
  query: string,
): v is CandidatePage {
  if (
    !exact(
      v,
      'organizationId teamId missionId taskId page pageSize total query items',
    )
  )
    return false;
  const p = v as unknown as CandidatePage;
  return (
    sameScope(p, s) &&
    Number.isInteger(page) &&
    page >= 0 &&
    page <= 100000 &&
    p.page === page &&
    p.pageSize === 20 &&
    Number.isSafeInteger(p.total) &&
    p.total >= 0 &&
    p.query === query &&
    text(query, 100) &&
    strip(query) === query &&
    Array.isArray(p.items) &&
    p.items.length === Math.min(20, Math.max(p.total - page * 20, 0)) &&
    p.items.every(candidate) &&
    new Set(p.items.map(t => t.id)).size === p.items.length &&
    p.items.every(
      (t, i) =>
        t.id !== s.taskId &&
        (i === 0 ||
          titleOrder(p.items[i - 1].title, t.title) < 0 ||
          (titleOrder(p.items[i - 1].title, t.title) === 0 &&
            p.items[i - 1].id < t.id)),
    )
  );
}
export function validReceipt(
  v: unknown,
  s: Scope,
  b?: Body,
  before?: Task[],
): v is Receipt {
  if (
    !exact(
      v,
      'organizationId teamId missionId taskId eventId revision committedAt before after',
    )
  )
    return false;
  const r = v as unknown as Receipt;
  return (
    sameScope(r, s) &&
    uuid(r.eventId) &&
    revision(r.revision) &&
    r.revision > 0 &&
    timestamp(r.committedAt) &&
    taskSet(r.before, s.taskId) &&
    taskSet(r.after, s.taskId) &&
    !sameIds(r.before, r.after) &&
    (!b ||
      (r.revision === b.expectedRevision + 1 &&
        r.after.length === b.dependencies.length &&
        r.after.every((t, i) => t.id === b.dependencies[i].taskId))) &&
    (!before ||
      (sameIds(r.before, before) &&
        r.before.every(
          (t, i) =>
            t.title === before[i].title && t.status === before[i].status,
        )))
  );
}

// Check lexical integers and duplicate keys before JSON.parse erases them.
export function decodeData(bytes: ArrayBuffer): unknown {
  const input = new TextDecoder('utf-8', {fatal: true}).decode(bytes);
  let at = 0;
  const white = () => {
    while (/[\x20\t\r\n]/.test(input[at] ?? '\0')) at++;
  };
  const string = (): string => {
    const start = at++;
    while (at < input.length) {
      const c = input[at++];
      if (c === '\\') at++;
      else if (c === '"') return JSON.parse(input.slice(start, at));
    }
    throw Error('Invalid wire');
  };
  const value = (key = '', depth = 0): void => {
    if (depth > 32) throw Error('Invalid wire');
    white();
    const c = input[at];
    if (c === '{') {
      at++;
      white();
      const keys = new Set<string>();
      if (input[at] === '}') {
        at++;
        return;
      }
      while (at < input.length) {
        if (input[at] !== '"') throw Error('Invalid wire');
        const k = string();
        if (keys.has(k)) throw Error('Invalid wire');
        keys.add(k);
        white();
        if (input[at++] !== ':') throw Error('Invalid wire');
        value(k, depth + 1);
        white();
        if (input[at] === '}') {
          at++;
          return;
        }
        if (input[at++] !== ',') throw Error('Invalid wire');
        white();
      }
    } else if (c === '[') {
      at++;
      white();
      if (input[at] === ']') {
        at++;
        return;
      }
      while (at < input.length) {
        value('', depth + 1);
        white();
        if (input[at] === ']') {
          at++;
          return;
        }
        if (input[at++] !== ',') throw Error('Invalid wire');
      }
    } else if (c === '"') {
      string();
      return;
    } else {
      const token =
        /^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(
          input.slice(at),
        )?.[0];
      if (!token) throw Error('Invalid wire');
      if (
        ['revision', 'expectedRevision', 'page', 'pageSize', 'total'].includes(
          key,
        ) &&
        !/^(?:0|[1-9][0-9]*)$/.test(token)
      )
        throw Error('Invalid wire');
      at += token.length;
      return;
    }
    throw Error('Invalid wire');
  };
  value();
  white();
  if (at !== input.length) throw Error('Invalid wire');
  const envelope: unknown = JSON.parse(input);
  if (
    !exact(envelope, 'data meta') ||
    !envelope['meta'] ||
    typeof envelope['meta'] !== 'object' ||
    Array.isArray(envelope['meta'])
  )
    throw Error('Invalid envelope');
  return envelope['data'];
}

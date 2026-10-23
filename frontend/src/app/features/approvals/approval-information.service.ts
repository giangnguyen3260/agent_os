import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {switchMap, timeout} from 'rxjs';
import {uuid} from '../teams/team.service';
// Shared with the backend: Java/ECMAScript whitespace union, only at text edges.
function informationSpace(code: number): boolean {
  return (
    (code >= 9 && code <= 13) ||
    (code >= 28 && code <= 32) ||
    code === 160 ||
    code === 5760 ||
    (code >= 8192 && code <= 8202) ||
    code === 8232 ||
    code === 8233 ||
    code === 8239 ||
    code === 8287 ||
    code === 12288 ||
    code === 65279
  );
}
export function informationText(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && informationSpace(value.charCodeAt(start))) start++;
  while (end > start && informationSpace(value.charCodeAt(end - 1))) end--;
  return value.slice(start, end);
}
export interface InformationEntry {
  id: string;
  kind: 'QUESTION' | 'ANSWER';
  actorId: string;
  actorName: string;
  text: string;
  evidence: string | null;
  createdAt: string;
}
export interface Information {
  approvalId: string;
  revision: number;
  canRequest: boolean;
  canReply: boolean;
  entries: InformationEntry[];
  latestQuestionId: string | null;
}
export interface InformationResult {
  approvalId: string;
  revision: number;
  status: 'PENDING' | 'NEEDS_INFORMATION';
  entryId: string;
}
export type InformationOperation = 'request-information' | 'reply';
export type InformationBody =
  | {revision: number; question: string}
  | {
      revision: number;
      questionId: string;
      answer: string;
      evidence: string | null;
    };
const exact = (v: unknown, keys: string) =>
  !!v &&
  typeof v === 'object' &&
  Object.keys(v).sort().join(' ') === keys.split(' ').sort().join(' ');
const text = (v: unknown, max: number) =>
  typeof v === 'string' && !!informationText(v) && v.length <= max;
export function validInformation(
  v: Information | undefined,
  id: string,
): v is Information {
  if (
    !v ||
    !exact(
      v,
      'approvalId revision canRequest canReply entries latestQuestionId',
    ) ||
    v.approvalId !== id ||
    !Number.isSafeInteger(v.revision) ||
    v.revision < 0 ||
    v.revision > 40 ||
    typeof v.canRequest !== 'boolean' ||
    typeof v.canReply !== 'boolean' ||
    (v.canRequest && v.canReply) ||
    !Array.isArray(v.entries) ||
    v.entries.length !== v.revision ||
    (v.latestQuestionId !== null && !uuid(v.latestQuestionId))
  )
    return false;
  const ids = new Set<string>();
  for (const [i, e] of v.entries.entries()) {
    if (
      !exact(e, 'id kind actorId actorName text evidence createdAt') ||
      !uuid(e.id) ||
      ids.has(e.id) ||
      !uuid(e.actorId) ||
      !text(e.actorName, 255) ||
      !text(e.text, 2000) ||
      e.kind !== (i % 2 === 0 ? 'QUESTION' : 'ANSWER') ||
      (e.evidence !== null && !text(e.evidence, 1000)) ||
      (e.kind === 'QUESTION' && e.evidence !== null) ||
      typeof e.createdAt !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T/.test(e.createdAt) ||
      !Number.isFinite(Date.parse(e.createdAt))
    )
      return false;
    ids.add(e.id);
  }
  const latest =
    [...v.entries].reverse().find(e => e.kind === 'QUESTION')?.id ?? null;
  return (
    v.latestQuestionId === latest &&
    (!v.canReply || v.revision % 2 === 1) &&
    (!v.canRequest || (v.revision % 2 === 0 && v.revision < 40))
  );
}
export function validInformationResult(
  v: InformationResult | undefined,
  id: string,
): v is InformationResult {
  return (
    !!v &&
    exact(v, 'approvalId revision status entryId') &&
    v.approvalId === id &&
    Number.isSafeInteger(v.revision) &&
    v.revision > 0 &&
    v.revision <= 40 &&
    uuid(v.entryId) &&
    v.status === (v.revision % 2 === 1 ? 'NEEDS_INFORMATION' : 'PENDING')
  );
}
@Injectable({providedIn: 'root'})
export class ApprovalInformationService {
  private readonly http = inject(HttpClient);
  private root(org: string, id: string) {
    return `/api/v1/organizations/${encodeURIComponent(org)}/approvals/${encodeURIComponent(id)}`;
  }
  get(org: string, id: string) {
    return this.http
      .get<{data: Information}>(`${this.root(org, id)}/information`)
      .pipe(timeout(30000));
  }
  receipt(org: string, id: string, key: string) {
    return this.http
      .get<{
        data: InformationResult | null;
      }>(
        `${this.root(org, id)}/information/receipts/${encodeURIComponent(key)}`,
      )
      .pipe(timeout(30000));
  }
  send(
    org: string,
    id: string,
    operation: InformationOperation,
    key: string,
    body: InformationBody,
  ) {
    return this.http
      .get<{data: {headerName: string; token: string}}>('/api/v1/auth/csrf')
      .pipe(
        timeout(30000),
        switchMap(csrf => {
          if (!csrf?.data?.headerName || !csrf.data.token)
            throw new Error('CSRF unavailable');
          return this.http
            .post<{
              data: InformationResult;
            }>(`${this.root(org, id)}/${operation}`, body, {
              headers: new HttpHeaders()
                .set(csrf.data.headerName, csrf.data.token)
                .set('Idempotency-Key', key),
            })
            .pipe(timeout(30000));
        }),
      );
  }
}

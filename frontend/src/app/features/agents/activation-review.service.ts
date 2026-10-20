import {HttpClient} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {timeout} from 'rxjs';

export const activationCheckCodes = [
  'DRAFT_STATUS',
  'CONTRACT',
  'MODEL_SELECTION',
  'STRUCTURED_LIMITS',
  'TEAM_ACTIVE',
  'TEAM_ENVELOPE',
  'MODEL_AUTHORIZATION',
  'TOOL_AUTHORIZATION',
  'EXECUTION_RUNTIME',
] as const;
export interface ActivationReview {
  organizationId: string;
  agentId: string;
  name: string;
  status: string;
  revision: number;
  checkedAt: string;
  activationAllowed: boolean;
  checks: {
    code: (typeof activationCheckCodes)[number];
    label: string;
    status: 'PASS' | 'BLOCKED' | 'UNAVAILABLE';
    message: string;
  }[];
}
@Injectable({providedIn: 'root'})
export class ActivationReviewService {
  private readonly http = inject(HttpClient);
  get(org: string, agent: string) {
    return this.http
      .get<{
        data: ActivationReview;
      }>(
        `/api/v1/organizations/${encodeURIComponent(org)}/agents/${encodeURIComponent(agent)}/activation-review`,
      )
      .pipe(timeout(30000));
  }
}

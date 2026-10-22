import {inject, Injectable} from '@angular/core';
import {CanActivateFn} from '@angular/router';

@Injectable({providedIn: 'root'})
export class ApprovalNavigationState {
  check: ((org: string, approvalId: string) => boolean) | null = null;
}
export const approvalDetailNavigationGuard: CanActivateFn = route =>
  inject(ApprovalNavigationState).check?.(
    route.paramMap.get('id') || '',
    route.paramMap.get('approvalId') || '',
  ) ?? true;

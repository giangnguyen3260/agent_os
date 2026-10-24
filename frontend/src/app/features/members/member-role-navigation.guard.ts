import {inject, Injectable} from '@angular/core';
import {CanActivateFn} from '@angular/router';
@Injectable({providedIn: 'root'})
export class MemberRoleNavigationState {
  check: ((org: string, account: string) => boolean) | null = null;
}
export const memberRoleNavigationGuard: CanActivateFn = route =>
  inject(MemberRoleNavigationState).check?.(
    route.paramMap.get('id') || '',
    route.paramMap.get('accountId') || '',
  ) ?? true;

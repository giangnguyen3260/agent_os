import {inject, Injectable} from '@angular/core';
import {CanActivateFn} from '@angular/router';
@Injectable({providedIn: 'root'})
export class GrantNavigationState {
  check: ((org: string, account: string, grant: string) => boolean) | null =
    null;
}
export const grantNavigationGuard: CanActivateFn = route =>
  inject(GrantNavigationState).check?.(
    route.paramMap.get('id') || '',
    route.paramMap.get('accountId') || '',
    route.paramMap.get('grantId') || '',
  ) ?? true;

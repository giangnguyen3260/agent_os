import {inject} from '@angular/core';
import {CanActivateFn, CanDeactivateFn, Router} from '@angular/router';
import {AuthService} from '../../core/auth/auth.service';
import {uuid} from './invitation-validation';
export const invitationAdminGuard: CanActivateFn = async route => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const org = route.paramMap.get('id');
  try {
    if (!uuid(org)) return router.createUrlTree(['/workspace']);
    const actor = await auth.session();
    const context = await auth.getWorkspace(org);
    return actor.id === auth.user()?.id &&
      context.id === org &&
      context.role === 'ADMIN'
      ? true
      : router.createUrlTree(['/workspace']);
  } catch {
    return router.createUrlTree(['/workspace']);
  }
};
export const invitationLeaveGuard: CanDeactivateFn<{
  canLeave(): boolean;
}> = component => component.canLeave();

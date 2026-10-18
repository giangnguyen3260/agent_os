import {inject} from '@angular/core';
import {CanActivateFn, Router} from '@angular/router';
import {AuthFailure, AuthService} from './auth.service';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  try {
    await auth.session();
    return true;
  } catch (error) {
    return router.createUrlTree(['/login'], {
      queryParams:
        error instanceof AuthFailure && error.kind === 'unauthorized'
          ? {}
          : {sessionError: 'unavailable'},
    });
  }
};

export const previewGuard: CanActivateFn = () =>
  inject(Router).createUrlTree(['/workspace']);

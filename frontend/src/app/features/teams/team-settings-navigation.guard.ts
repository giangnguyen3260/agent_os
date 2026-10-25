import {inject, Injectable} from '@angular/core';
import {CanActivateFn, CanDeactivateFn} from '@angular/router';

export interface TeamSettingsNavigation {
  id: string;
  teamId: string;
  canLeave(): boolean;
}
/** The same instance checks identity changes when Angular reuses the component. */
@Injectable({providedIn: 'root'})
export class TeamSettingsNavigationState {
  active: TeamSettingsNavigation | null = null;
}
export const teamSettingsNavigationGuard: CanActivateFn = route => {
  const current = inject(TeamSettingsNavigationState).active;
  return (
    !current ||
    (current.id === route.paramMap.get('id') &&
      current.teamId === route.paramMap.get('teamId')) ||
    current.canLeave()
  );
};
export const teamSettingsLeaveGuard: CanDeactivateFn<
  TeamSettingsNavigation
> = component => component.canLeave();
export const teamSettingsGuard = teamSettingsNavigationGuard;

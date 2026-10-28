import {inject, Injectable} from '@angular/core';
import {CanActivateFn, CanDeactivateFn} from '@angular/router';
export interface MissionIntakeNavigation {
  id: string;
  teamId: string;
  missionId: string;
  canLeave(): boolean;
}
@Injectable({providedIn: 'root'})
export class MissionIntakeNavigationState {
  active: MissionIntakeNavigation | null = null;
}
export const missionIntakeNavigationGuard: CanActivateFn = route => {
  const c = inject(MissionIntakeNavigationState).active;
  return (
    !c ||
    (['id', 'teamId', 'missionId'] as const).every(
      k => c[k] === route.paramMap.get(k),
    ) ||
    c.canLeave()
  );
};
export const missionIntakeLeaveGuard: CanDeactivateFn<
  MissionIntakeNavigation
> = c => c.canLeave();
export const missionIntakeGuard = missionIntakeNavigationGuard;

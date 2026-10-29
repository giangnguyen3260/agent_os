import {inject, Injectable} from '@angular/core';
import {CanActivateFn, CanDeactivateFn} from '@angular/router';
export interface TaskOwnerNavigation {
  id: string;
  teamId: string;
  missionId: string;
  taskId: string;
  canLeave(): boolean;
}
@Injectable({providedIn: 'root'})
export class TaskOwnerNavigationState {
  active: TaskOwnerNavigation | null = null;
}
export const taskOwnerNavigationGuard: CanActivateFn = route => {
  const c = inject(TaskOwnerNavigationState).active;
  return (
    !c ||
    (['id', 'teamId', 'missionId', 'taskId'] as const).every(
      k => c[k] === route.paramMap.get(k),
    ) ||
    c.canLeave()
  );
};
export const taskOwnerLeaveGuard: CanDeactivateFn<TaskOwnerNavigation> = c =>
  c.canLeave();
export const taskOwnerGuard = taskOwnerNavigationGuard;

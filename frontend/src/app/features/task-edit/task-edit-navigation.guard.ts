import {inject, Injectable} from '@angular/core';
import {CanActivateFn, CanDeactivateFn} from '@angular/router';
export interface TaskEditNavigation {
  id: string;
  teamId: string;
  missionId: string;
  taskId: string;
  canLeave(): boolean;
}
@Injectable({providedIn: 'root'})
export class TaskEditNavigationState {
  active: TaskEditNavigation | null = null;
}
export const taskEditNavigationGuard: CanActivateFn = route => {
  const c = inject(TaskEditNavigationState).active;
  return (
    !c ||
    (['id', 'teamId', 'missionId', 'taskId'] as const).every(
      k => c[k] === route.paramMap.get(k),
    ) ||
    c.canLeave()
  );
};
export const taskEditLeaveGuard: CanDeactivateFn<TaskEditNavigation> = c =>
  c.canLeave();
export const taskEditGuard = taskEditNavigationGuard;

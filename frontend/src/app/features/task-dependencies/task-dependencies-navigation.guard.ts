import {inject, Injectable} from '@angular/core';
import {CanActivateFn, CanDeactivateFn} from '@angular/router';
export interface TaskDependenciesNavigation {
  id: string;
  teamId: string;
  missionId: string;
  taskId: string;
  canLeave(): boolean | Promise<boolean>;
}
@Injectable({providedIn: 'root'})
export class TaskDependenciesNavigationState {
  active: TaskDependenciesNavigation | null = null;
}
export const taskDependenciesNavigationGuard: CanActivateFn = route => {
  const c = inject(TaskDependenciesNavigationState).active;
  return (
    !c ||
    (['id', 'teamId', 'missionId', 'taskId'] as const).every(
      k => c[k] === route.paramMap.get(k),
    ) ||
    c.canLeave()
  );
};
export const taskDependenciesLeaveGuard: CanDeactivateFn<
  TaskDependenciesNavigation
> = c => c.canLeave();
export const taskDependenciesGuard = taskDependenciesNavigationGuard;

import {Routes} from '@angular/router';

export const routes: Routes = [
  {path: '', pathMatch: 'full', redirectTo: 'dashboard'},
  {
    path: 'dashboard',
    loadComponent: () =>
      import('./features/dashboard/dashboard-page.component').then(
        module => module.DashboardPageComponent,
      ),
  },
  {
    path: 'organization',
    loadComponent: () =>
      import('./features/organization/organization-page.component').then(
        module => module.OrganizationPageComponent,
      ),
  },
  {
    path: 'agents',
    loadComponent: () =>
      import('./features/agents/agent-list-page.component').then(
        module => module.AgentListPageComponent,
      ),
  },
  {
    path: 'missions',
    loadComponent: () =>
      import('./features/missions/mission-list-page.component').then(
        module => module.MissionListPageComponent,
      ),
  },
  {
    path: 'approvals',
    loadComponent: () =>
      import('./features/approvals/approval-list-page.component').then(
        module => module.ApprovalListPageComponent,
      ),
  },
  {
    path: '**',
    loadComponent: () =>
      import('./features/not-found/not-found-page.component').then(
        module => module.NotFoundPageComponent,
      ),
  },
];

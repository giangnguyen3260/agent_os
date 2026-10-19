import {Routes} from '@angular/router';
import {authGuard, previewGuard} from './core/auth/auth.guard';

export const routes: Routes = [
  {path: '', pathMatch: 'full', redirectTo: 'workspace'},
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login-page.component').then(
        module => module.LoginPageComponent,
      ),
  },
  {
    path: 'forgot-password',
    loadComponent: () =>
      import('./features/auth/forgot-password-page.component').then(
        module => module.ForgotPasswordPageComponent,
      ),
  },
  {
    path: 'reset',
    loadComponent: () =>
      import('./features/auth/reset-password-page.component').then(
        module => module.ResetPasswordPageComponent,
      ),
  },
  {
    path: 'workspace',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/workspace/workspace-page.component').then(
        module => module.WorkspacePageComponent,
      ),
  },
  {
    path: '',
    canActivate: [authGuard, previewGuard],
    loadComponent: () =>
      import('./core/layout/shell-layout.component').then(
        module => module.ShellLayoutComponent,
      ),
    children: [
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
    ],
  },
  {
    path: '**',
    canActivate: [authGuard, previewGuard],
    loadComponent: () =>
      import('./features/not-found/not-found-page.component').then(
        module => module.NotFoundPageComponent,
      ),
  },
];

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
    path: 'workspace/:id/missions',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/missions/mission-list-page.component').then(
        module => module.MissionListPageComponent,
      ),
  },
  {
    path: 'workspace/:id/agents',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/agents/agent-list-page.component').then(
        module => module.AgentListPageComponent,
      ),
  },
  {
    path: 'workspace/:id/agents/new',
    canDeactivate: [
      (
        component: import('./features/agents/agent-page.component').AgentPageComponent,
      ) => component.canLeave(),
    ],
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/agents/agent-page.component').then(
        module => module.AgentPageComponent,
      ),
  },
  {
    path: 'workspace/:id/agents/:agentId/activation-review',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/agents/activation-review-page.component').then(
        module => module.ActivationReviewPageComponent,
      ),
  },
  {
    path: 'workspace/:id/agents/:agentId/studio',
    canActivate: [authGuard],
    canDeactivate: [
      (
        component: import('./features/agents/agent-studio-page.component').AgentStudioPageComponent,
      ) => component.canLeave(),
    ],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/agents/agent-studio-page.component').then(
        module => module.AgentStudioPageComponent,
      ),
  },
  {
    path: 'workspace/:id/agents/:agentId',
    canDeactivate: [
      (
        component: import('./features/agents/agent-page.component').AgentPageComponent,
      ) => component.canLeave(),
    ],
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/agents/agent-page.component').then(
        module => module.AgentPageComponent,
      ),
  },
  {
    path: 'workspace/:id/teams',
    pathMatch: 'full',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/teams/team-directory.component').then(
        module => module.TeamDirectoryComponent,
      ),
  },
  {
    path: 'workspace/:id/teams/new',
    canDeactivate: [
      (
        component: import('./features/teams/team-page.component').TeamPageComponent,
      ) => component.canLeave(),
    ],
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/teams/team-page.component').then(
        module => module.TeamPageComponent,
      ),
  },
  {
    path: 'workspace/:id/teams/:teamId/workspace',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/teams/team-workspace.component').then(
        module => module.TeamWorkspaceComponent,
      ),
  },
  {
    path: 'workspace/:id/teams/:teamId/missions/new',
    canActivate: [authGuard],
    canDeactivate: [
      (
        component: import('./features/missions/mission-page.component').MissionPageComponent,
      ) => component.canLeave(),
    ],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/missions/mission-page.component').then(
        module => module.MissionPageComponent,
      ),
  },
  {
    path: 'workspace/:id/teams/:teamId/missions/:missionId/tasks/new',
    canActivate: [authGuard],
    canDeactivate: [
      (
        component: import('./features/missions/new-task-create.component').NewTaskCreateComponent,
      ) => component.canLeave(),
    ],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/missions/new-task-create.component').then(
        m => m.NewTaskCreateComponent,
      ),
  },
  {
    path: 'workspace/:id/teams/:teamId/missions/:missionId/tasks/:taskId',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/missions/task-detail.component').then(
        module => module.TaskDetailComponent,
      ),
  },
  {
    path: 'workspace/:id/teams/:teamId/missions/:missionId/workspace',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/missions/mission-workspace.component').then(
        module => module.MissionWorkspaceComponent,
      ),
  },
  {
    path: 'workspace/:id/teams/:teamId/missions/:missionId',
    canActivate: [authGuard],
    canDeactivate: [
      (
        component: import('./features/missions/mission-page.component').MissionPageComponent,
      ) => component.canLeave(),
    ],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/missions/mission-page.component').then(
        module => module.MissionPageComponent,
      ),
  },
  {
    path: 'workspace/:id/teams/:teamId',
    canDeactivate: [
      (
        component: import('./features/teams/team-page.component').TeamPageComponent,
      ) => component.canLeave(),
    ],
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/teams/team-page.component').then(
        module => module.TeamPageComponent,
      ),
  },
  {
    path: 'workspace/:id/dashboard',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/dashboard/dashboard-page.component').then(
        module => module.DashboardPageComponent,
      ),
  },
  {
    path: 'workspace/:id',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/workspace/workspace-context-page.component').then(
        module => module.WorkspaceContextPageComponent,
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
      {path: 'dashboard', pathMatch: 'full', redirectTo: '/workspace'},
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

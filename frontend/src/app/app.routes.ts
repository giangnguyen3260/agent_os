import {
  taskEditNavigationGuard,
  taskEditLeaveGuard,
} from './features/task-edit/task-edit-navigation.guard';
import {
  teamSettingsNavigationGuard,
  teamSettingsLeaveGuard,
} from './features/teams/team-settings-navigation.guard';
import {
  invitationAdminGuard,
  invitationLeaveGuard,
} from './features/invitations/invitation-navigation.guard';
import {memberRoleNavigationGuard} from './features/members/member-role-navigation.guard';
import {Routes} from '@angular/router';
import {grantNavigationGuard} from './features/members/grant-navigation.guard';
import {authGuard, previewGuard} from './core/auth/auth.guard';
import {approvalDetailNavigationGuard} from './features/approvals/approval-navigation.guard';

export const routes: Routes = [
  {
    path: 'workspace/:id/teams/:teamId/missions/:missionId/tasks/:taskId/history',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/task-history/task-history.component').then(
        m => m.TaskHistoryComponent,
      ),
  },
  {
    path: 'workspace/:id/teams/:teamId/missions/:missionId/tasks/:taskId/edit',
    canActivate: [authGuard, taskEditNavigationGuard],
    canDeactivate: [taskEditLeaveGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/task-edit/task-draft-edit.component').then(
        module => module.TaskDraftEditComponent,
      ),
  },
  {
    path: 'workspace/:id/trace',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/trace/trace-explorer.component').then(
        module => module.TraceExplorerComponent,
      ),
  },
  {
    path: 'invitation',
    canDeactivate: [invitationLeaveGuard],
    loadComponent: () =>
      import('./features/invitations/invitation-accept.component').then(
        m => m.InvitationAcceptComponent,
      ),
  },
  {
    path: 'workspace/:id/invitations/new',
    canActivate: [invitationAdminGuard],
    canDeactivate: [invitationLeaveGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/invitations/invitation-admin.component').then(
        m => m.InvitationAdminComponent,
      ),
  },
  {
    path: 'workspace/:id/invitations/:invitationId',
    canActivate: [invitationAdminGuard],
    canDeactivate: [invitationLeaveGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/invitations/invitation-admin.component').then(
        m => m.InvitationAdminComponent,
      ),
  },
  {
    path: 'workspace/:id/invitations',
    canActivate: [invitationAdminGuard],
    canDeactivate: [invitationLeaveGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/invitations/invitation-admin.component').then(
        m => m.InvitationAdminComponent,
      ),
  },

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
    path: 'workspace/:id/members/:accountId/role',
    canActivate: [authGuard, memberRoleNavigationGuard],
    runGuardsAndResolvers: 'always',
    canDeactivate: [
      (
        component: import('./features/members/member-role-management.component').MemberRoleManagementComponent,
      ) => component.canLeave(),
    ],
    loadComponent: () =>
      import('./features/members/member-role-management.component').then(
        module => module.MemberRoleManagementComponent,
      ),
  },
  {
    path: 'workspace/:id/members/:accountId/approval-grants/new',
    canActivate: [authGuard, grantNavigationGuard],
    runGuardsAndResolvers: 'always',
    canDeactivate: [
      (
        component: import('./features/members/grant-management.component').GrantManagementComponent,
      ) => component.canLeave(),
    ],
    loadComponent: () =>
      import('./features/members/grant-management.component').then(
        module => module.GrantManagementComponent,
      ),
  },
  {
    path: 'workspace/:id/members/:accountId/approval-grants/:grantId/revoke',
    canActivate: [authGuard, grantNavigationGuard],
    runGuardsAndResolvers: 'always',
    canDeactivate: [
      (
        component: import('./features/members/grant-management.component').GrantManagementComponent,
      ) => component.canLeave(),
    ],
    loadComponent: () =>
      import('./features/members/grant-management.component').then(
        module => module.GrantManagementComponent,
      ),
  },
  {
    path: 'workspace/:id/members/:accountId/approval-grants',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/members/member-access.component').then(
        module => module.MemberAccessComponent,
      ),
  },
  {
    path: 'workspace/:id/members',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/members/member-access.component').then(
        module => module.MemberAccessComponent,
      ),
  },
  {
    path: 'workspace/:id/approvals/:approvalId/information',
    canActivate: [authGuard, approvalDetailNavigationGuard],
    runGuardsAndResolvers: 'always',
    canDeactivate: [
      (
        component: import('./features/approvals/approval-information.component').ApprovalInformationComponent,
      ) => component.canLeave(),
    ],
    loadComponent: () =>
      import('./features/approvals/approval-information.component').then(
        module => module.ApprovalInformationComponent,
      ),
  },
  {
    path: 'workspace/:id/approvals/:approvalId',
    canActivate: [authGuard, approvalDetailNavigationGuard],
    runGuardsAndResolvers: 'always',
    canDeactivate: [
      (
        component: import('./features/approvals/approval-detail.component').ApprovalDetailComponent,
      ) => component.canLeave(),
    ],
    loadComponent: () =>
      import('./features/approvals/approval-detail.component').then(
        module => module.ApprovalDetailComponent,
      ),
  },
  {
    path: 'workspace/:id/approvals',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/approvals/approval-list-page.component').then(
        module => module.ApprovalListPageComponent,
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
    path: 'workspace/:id/agents/:agentId/permissions',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/agents/agent-permissions.component').then(
        module => module.AgentPermissionsComponent,
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
    path: 'workspace/:id/teams/:teamId/settings',
    canActivate: [authGuard, teamSettingsNavigationGuard],
    canDeactivate: [teamSettingsLeaveGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/teams/team-settings.component').then(
        module => module.TeamSettingsComponent,
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
    path: 'workspace/:id/organization-map',
    canActivate: [authGuard],
    runGuardsAndResolvers: 'always',
    loadComponent: () =>
      import('./features/organization/organization-map.component').then(
        module => module.OrganizationMapComponent,
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

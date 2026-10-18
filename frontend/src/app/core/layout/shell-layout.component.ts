import {NgTemplateOutlet} from '@angular/common';
import {Component} from '@angular/core';
import {RouterLink, RouterLinkActive} from '@angular/router';

@Component({
  selector: 'app-shell-layout',
  standalone: true,
  imports: [NgTemplateOutlet, RouterLink, RouterLinkActive],
  template: `
    <ng-template #navigation>
      @for (item of navigationItems; track item.path) {
        <a
          [routerLink]="item.path"
          routerLinkActive="active"
          ariaCurrentWhenActive="page"
          (click)="mobileNavigation.close()"
        >
          <span>{{ item.label }}</span>
          <small>{{ item.description }}</small>
        </a>
      }
    </ng-template>

    <div class="shell">
      <aside class="sidebar">
        <div class="brand-row">
          <div class="brand-mark" aria-hidden="true">A</div>
          <div>
            <div class="brand">AgentOS</div>
            <div class="brand-subtitle">Organization Control</div>
          </div>
        </div>
        <div class="workspace-card">
          <span class="workspace-label">Sample workspace</span>
          <strong>Agent_AI</strong>
          <small>Engineering org</small>
        </div>
        <nav aria-label="Main navigation">
          <ng-container [ngTemplateOutlet]="navigation" />
        </nav>
        <div class="sidebar-footer">Preview · Sample data</div>
      </aside>
      <main id="main-content">
        <header class="topbar">
          <button
            type="button"
            class="button secondary menu-button"
            aria-haspopup="dialog"
            aria-controls="mobile-navigation"
            (click)="mobileNavigation.showModal()"
          >
            Menu
          </button>
          <div class="topbar-title">
            <strong>Build and manage your own AI organization</strong>
            <span>Human-governed autonomous execution</span>
          </div>
          <a class="approval-link" routerLink="/approvals">Approvals</a>
        </header>
        <section class="content">
          <p class="preview-notice">
            Preview workspace. Figures and statuses are illustrative; runtime
            data is not connected.
          </p>
          <ng-content />
        </section>
      </main>
    </div>

    <dialog
      #mobileNavigation
      id="mobile-navigation"
      aria-labelledby="navigation-title"
    >
      <div class="dialog-header">
        <h2 id="navigation-title">Navigation</h2>
        <button
          type="button"
          class="button secondary"
          autofocus
          (click)="mobileNavigation.close()"
        >
          Close
        </button>
      </div>
      <nav aria-label="Mobile navigation">
        <ng-container [ngTemplateOutlet]="navigation" />
      </nav>
    </dialog>
  `,
  styles: [
    `
      .shell {
        display: grid;
        grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
        min-height: 100vh;
      }
      .sidebar {
        background: var(--sidebar);
        color: var(--sidebar-text);
        display: flex;
        flex-direction: column;
        gap: 24px;
        padding: 24px 16px;
      }
      .brand-row {
        align-items: center;
        display: flex;
        gap: 10px;
      }
      .brand-mark {
        background: var(--primary-soft);
        border-radius: 8px;
        color: var(--primary-strong);
        display: grid;
        flex-shrink: 0;
        font-weight: 800;
        height: 36px;
        place-items: center;
        width: 36px;
      }
      .brand {
        font-size: 20px;
        font-weight: 700;
      }
      .brand-subtitle,
      .workspace-label {
        color: var(--sidebar-muted);
        font-size: 12px;
      }
      .workspace-card {
        background: var(--sidebar-soft);
        border: 1px solid var(--sidebar-line);
        border-radius: 8px;
        display: grid;
        gap: 4px;
        padding: 14px;
      }
      .workspace-card small {
        color: var(--sidebar-muted);
      }
      nav {
        display: grid;
        gap: 8px;
      }
      nav a {
        border-radius: 8px;
        display: grid;
        gap: 2px;
        padding: 11px 12px;
      }
      nav a span {
        font-weight: 700;
      }
      nav a small {
        color: var(--sidebar-muted);
      }
      nav a.active,
      nav a:hover {
        background: var(--sidebar-soft);
      }
      main {
        min-width: 0;
      }
      .topbar {
        align-items: center;
        background: var(--surface);
        border-bottom: 1px solid var(--line);
        display: flex;
        gap: 16px;
        justify-content: space-between;
        min-height: 64px;
        padding: 12px var(--content-padding);
      }
      .topbar strong {
        display: block;
        font-size: 14px;
      }
      .topbar span {
        color: var(--muted);
        display: block;
        font-size: 12px;
        margin-top: 2px;
      }
      .approval-link {
        display: inline-flex;
        align-items: center;
        min-height: 44px;
        min-width: 44px;
        color: var(--primary-strong);
        font-size: 14px;
        font-weight: 700;
      }
      .content {
        padding: var(--content-padding);
      }
      .preview-notice {
        color: var(--muted);
        font-size: 13px;
        margin: 0 0 24px;
      }
      .sidebar-footer {
        color: var(--sidebar-muted);
        font-size: 13px;
        margin-top: auto;
      }
      .menu-button {
        display: none;
      }
      dialog {
        background: var(--sidebar);
        border: 1px solid var(--sidebar-line);
        border-radius: 12px;
        color: var(--sidebar-text);
        max-height: calc(100dvh - 32px);
        padding: 24px;
        width: min(360px, calc(100% - 32px));
      }
      dialog::backdrop {
        background: var(--overlay);
      }
      .dialog-header {
        align-items: center;
        display: flex;
        gap: 16px;
        justify-content: space-between;
        margin-bottom: 16px;
      }
      .dialog-header h2 {
        font-size: 18px;
        margin: 0;
      }
      @media (max-width: 1023px) {
        .shell {
          grid-template-columns: minmax(0, 1fr);
        }
        .sidebar {
          display: none;
        }
        .menu-button {
          display: inline-flex;
        }
        .topbar {
          flex-wrap: wrap;
        }
        .topbar-title {
          flex: 1;
          min-width: 160px;
        }
      }
    `,
  ],
})
export class ShellLayoutComponent {
  readonly navigationItems = [
    {path: '/dashboard', label: 'Dashboard', description: 'Overview'},
    {path: '/organization', label: 'Organization', description: 'Teams & map'},
    {path: '/agents', label: 'Agents', description: 'Studio'},
    {path: '/missions', label: 'Missions', description: 'Execution'},
    {path: '/approvals', label: 'Approvals', description: 'Review queue'},
  ];
}

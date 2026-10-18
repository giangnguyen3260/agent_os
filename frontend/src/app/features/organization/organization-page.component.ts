import {Component} from '@angular/core';

@Component({
  selector: 'app-organization-page',
  standalone: true,
  template: `
    <div class="page">
      <section>
        <h1>Organization</h1>
        <p class="muted">
          Define workspace, teams, reporting lines, and the AI organization
          constitution.
        </p>
      </section>

      <section class="panel">
        <h2>Organization Map</h2>
        <div class="org-map">
          <div class="node human">You</div>
          <div class="line"></div>
          <div class="node manager">AI Manager</div>
          <div class="branches">
            <div class="node">Product</div>
            <div class="node">Engineering</div>
            <div class="node">QA</div>
          </div>
        </div>
      </section>
    </div>
  `,
  styles: [
    `
      h1,
      h2 {
        margin: 0 0 8px;
      }

      .org-map {
        align-items: center;
        display: grid;
        gap: 14px;
        justify-items: center;
        padding: 24px;
      }

      .node {
        background: #eef6ff;
        border: 1px solid #bfd7ff;
        border-radius: 8px;
        min-width: 140px;
        padding: 12px 16px;
        text-align: center;
      }

      .human {
        background: #ecfdf3;
        border-color: #b8e6c8;
      }

      .manager {
        background: #fff7ed;
        border-color: #fed7aa;
      }

      .line {
        background: #cbd5e1;
        height: 28px;
        width: 2px;
      }

      .branches {
        display: grid;
        gap: 14px;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        width: min(680px, 100%);
      }
    `,
  ],
})
export class OrganizationPageComponent {}

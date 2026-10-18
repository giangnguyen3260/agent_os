import {Component} from '@angular/core';

@Component({
  selector: 'app-agent-list-page',
  standalone: true,
  template: `
    <div class="page">
      <section class="page-heading">
        <div>
          <span class="eyebrow">Agent Studio</span>
          <h1>Agents</h1>
          <p class="muted">
            Configure role, model, skills, tools, autonomy, permissions, and
            budgets.
          </p>
        </div>
        <button type="button" class="button">Create Agent</button>
      </section>

      <section class="agent-grid">
        <article class="panel agent-card lead">
          <div class="agent-avatar">M</div>
          <div>
            <h2>Maya</h2>
            <p>Engineering Manager</p>
          </div>
          <span class="pill">GPT · Managed</span>
          <dl>
            <div>
              <dt>Success</dt>
              <dd>96%</dd>
            </div>
            <div>
              <dt>Cost</dt>
              <dd>$18.20</dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>Active</dd>
            </div>
          </dl>
        </article>

        <article class="panel agent-card">
          <div class="agent-avatar">E</div>
          <div>
            <h2>Emma</h2>
            <p>Solution Architect</p>
          </div>
          <span class="pill">Claude · Conditional</span>
          <dl>
            <div>
              <dt>Success</dt>
              <dd>92%</dd>
            </div>
            <div>
              <dt>Cost</dt>
              <dd>$24.80</dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>Active</dd>
            </div>
          </dl>
        </article>

        <article class="panel agent-card">
          <div class="agent-avatar">A</div>
          <div>
            <h2>Alex</h2>
            <p>Backend Developer</p>
          </div>
          <span class="pill">Claude · Execute</span>
          <dl>
            <div>
              <dt>Success</dt>
              <dd>89%</dd>
            </div>
            <div>
              <dt>Cost</dt>
              <dd>$21.00</dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>Active</dd>
            </div>
          </dl>
        </article>
      </section>

      <section class="panel">
        <h2>Permission Matrix</h2>
        <div class="permission-table">
          <div class="row header">
            <span>Capability</span>
            <span>Maya</span>
            <span>Emma</span>
            <span>Alex</span>
          </div>
          <div class="row">
            <span>Repository read</span>
            <strong>Allow</strong>
            <strong>Allow</strong>
            <strong>Allow</strong>
          </div>
          <div class="row">
            <span>Repository write</span>
            <strong>Approval</strong>
            <strong>Approval</strong>
            <strong>Allow</strong>
          </div>
          <div class="row">
            <span>Production deploy</span>
            <strong>Approval</strong>
            <strong>Block</strong>
            <strong>Block</strong>
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

      .page-heading {
        align-items: center;
        display: flex;
        justify-content: space-between;
        gap: 16px;
      }

      .agent-grid {
        display: grid;
        gap: 16px;
        grid-template-columns: repeat(3, minmax(0, 1fr));
      }

      .agent-card {
        display: grid;
        gap: 14px;
      }

      .agent-card.lead {
        border-color: #c7d7fe;
        background: #fbfdff;
      }

      .agent-avatar {
        align-items: center;
        background: #edf2ff;
        border-radius: 8px;
        color: #174ea6;
        display: grid;
        font-weight: 800;
        height: 44px;
        place-items: center;
        width: 44px;
      }

      .agent-card p {
        color: var(--muted);
        margin: 0;
      }

      dl {
        display: grid;
        gap: 10px;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        margin: 0;
      }

      dt {
        color: var(--muted);
        font-size: 12px;
      }

      dd {
        font-weight: 800;
        margin: 4px 0 0;
      }

      .permission-table {
        border: 1px solid var(--line);
        border-radius: 8px;
        overflow: hidden;
      }

      .row {
        display: grid;
        grid-template-columns: 1.5fr repeat(3, 1fr);
      }

      .row + .row {
        border-top: 1px solid var(--line);
      }

      .row span,
      .row strong {
        padding: 12px;
      }

      .header {
        background: var(--surface-soft);
        color: var(--muted);
        font-size: 13px;
        font-weight: 800;
      }

      .row strong {
        font-size: 14px;
      }

      @media (max-width: 960px) {
        .agent-grid {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class AgentListPageComponent {}

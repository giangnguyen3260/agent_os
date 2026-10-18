import {Component} from '@angular/core';

@Component({
  selector: 'app-dashboard-page',
  standalone: true,
  template: `
    <div class="page">
      <section class="hero">
        <div>
          <span class="eyebrow">Mission Control</span>
          <h1>Operate your AI engineering organization</h1>
          <p class="muted">
            Track autonomous work, approvals, cost, model calls, and execution
            health from one cockpit.
          </p>
        </div>
        <div class="hero-actions">
          <button type="button" class="button secondary">
            Review approvals
          </button>
          <button type="button" class="button">Create mission</button>
        </div>
      </section>

      <section class="stats">
        <article class="panel stat-card">
          <span class="label">Active agents</span>
          <strong>12</strong>
          <small class="trend positive">+3 this week</small>
        </article>
        <article class="panel stat-card">
          <span class="label">Running missions</span>
          <strong>4</strong>
          <small>2 waiting review</small>
        </article>
        <article class="panel stat-card attention">
          <span class="label">Pending approvals</span>
          <strong>3</strong>
          <small>1 high risk</small>
        </article>
        <article class="panel stat-card">
          <span class="label">October cost</span>
          <strong>$83.42</strong>
          <small>$116.58 remaining</small>
        </article>
      </section>

      <section class="dashboard-grid">
        <article class="panel active-missions">
          <div class="section-heading">
            <div>
              <h2>Active Missions</h2>
              <p class="muted">Current task graphs and execution states.</p>
            </div>
            <span class="pill">Sample data</span>
          </div>

          <div class="mission-list">
            <div class="mission-row">
              <div>
                <strong>Build customer registration API</strong>
                <span>Backend implementation · Alex</span>
              </div>
              <div class="progress-block">
                <span>68%</span>
                <div class="progress"><i style="width: 68%"></i></div>
              </div>
            </div>
            <div class="mission-row">
              <div>
                <strong>Design billing service boundary</strong>
                <span>Architecture review · Emma</span>
              </div>
              <div class="progress-block">
                <span>42%</span>
                <div class="progress"><i style="width: 42%"></i></div>
              </div>
            </div>
            <div class="mission-row">
              <div>
                <strong>QA onboarding workflow</strong>
                <span>Reviewer waiting · Quinn</span>
              </div>
              <div class="progress-block">
                <span>81%</span>
                <div class="progress"><i style="width: 81%"></i></div>
              </div>
            </div>
          </div>
        </article>

        <aside class="side-stack">
          <article class="panel">
            <div class="section-heading compact">
              <h2>Approvals</h2>
              <span class="pill">3</span>
            </div>
            <div class="approval-list">
              <div class="approval danger">
                <span class="status-dot red"></span>
                <div>
                  <strong>Database schema change</strong>
                  <small>Requires human approval</small>
                </div>
              </div>
              <div class="approval warning">
                <span class="status-dot amber"></span>
                <div>
                  <strong>Cost limit increase</strong>
                  <small>Mission budget +$5</small>
                </div>
              </div>
              <div class="approval">
                <span class="status-dot amber"></span>
                <div>
                  <strong>Create production branch</strong>
                  <small>Repository write action</small>
                </div>
              </div>
            </div>
          </article>

          <article class="panel">
            <h2>Agent Health</h2>
            <div class="agent-list">
              <div class="agent-row">
                <span class="status-dot green"></span>
                <strong>Maya</strong>
                <small>Manager · GPT</small>
              </div>
              <div class="agent-row">
                <span class="status-dot green"></span>
                <strong>Emma</strong>
                <small>Architect · Claude</small>
              </div>
              <div class="agent-row">
                <span class="status-dot amber"></span>
                <strong>Quinn</strong>
                <small>QA · Gemini</small>
              </div>
            </div>
          </article>
        </aside>
      </section>

      <section class="panel timeline-panel">
        <div class="section-heading">
          <div>
            <h2>Execution Timeline</h2>
            <p class="muted">Trace of recent orchestration decisions.</p>
          </div>
        </div>
        <div class="timeline">
          <div><time>14:10</time><span>Mission planned into 5 tasks</span></div>
          <div>
            <time>14:16</time><span>Architect approved API design</span>
          </div>
          <div>
            <time>14:28</time
            ><span>Governance paused database schema change</span>
          </div>
          <div>
            <time>14:34</time><span>QA rejected missing validation rule</span>
          </div>
        </div>
      </section>
    </div>
  `,
  styles: [
    `
      h1,
      h2,
      p {
        margin: 0 0 8px;
      }

      .hero {
        align-items: end;
        display: flex;
        gap: 20px;
        justify-content: space-between;
      }

      h1 {
        font-size: 32px;
        line-height: 1.15;
        margin-top: 8px;
      }

      h2 {
        font-size: 18px;
      }

      .hero-actions {
        display: flex;
        flex-wrap: wrap;
        gap: 10px;
        justify-content: flex-end;
      }

      .stats {
        display: grid;
        gap: 16px;
        grid-template-columns: repeat(4, minmax(0, 1fr));
      }

      .stat-card {
        min-height: 128px;
      }

      .stat-card.attention {
        border-color: #fed7aa;
        background: #fffaf5;
      }

      .label {
        color: var(--muted);
        display: block;
        font-size: 14px;
        margin-bottom: 10px;
      }

      strong {
        font-size: 28px;
      }

      .stat-card small {
        color: var(--muted);
        display: block;
        margin-top: 12px;
      }

      .trend.positive {
        color: var(--success);
      }

      .dashboard-grid {
        align-items: start;
        display: grid;
        gap: 18px;
        grid-template-columns: minmax(0, 1fr) 360px;
      }

      .section-heading {
        align-items: start;
        display: flex;
        gap: 16px;
        justify-content: space-between;
        margin-bottom: 18px;
      }

      .section-heading.compact {
        align-items: center;
        margin-bottom: 14px;
      }

      .mission-list,
      .approval-list,
      .agent-list,
      .side-stack {
        display: grid;
        gap: 12px;
      }

      .mission-row {
        align-items: center;
        border: 1px solid var(--line);
        border-radius: 8px;
        display: grid;
        gap: 18px;
        grid-template-columns: minmax(0, 1fr) 180px;
        padding: 14px;
      }

      .mission-row strong {
        display: block;
        font-size: 15px;
        margin-bottom: 4px;
      }

      .mission-row span,
      .approval small,
      .agent-row small {
        color: var(--muted);
        font-size: 13px;
      }

      .progress-block {
        display: grid;
        gap: 7px;
      }

      .progress-block > span {
        color: var(--text);
        font-size: 13px;
        font-weight: 800;
        text-align: right;
      }

      .progress {
        background: #e8eef7;
        border-radius: 999px;
        height: 8px;
        overflow: hidden;
      }

      .progress i {
        background: var(--primary);
        display: block;
        height: 100%;
      }

      .approval,
      .agent-row {
        align-items: center;
        border: 1px solid var(--line);
        border-radius: 8px;
        display: grid;
        gap: 10px;
        grid-template-columns: auto minmax(0, 1fr);
        padding: 12px;
      }

      .approval strong,
      .agent-row strong {
        display: block;
        font-size: 14px;
      }

      .timeline {
        display: grid;
        gap: 12px;
      }

      .timeline div {
        align-items: center;
        display: grid;
        gap: 12px;
        grid-template-columns: 64px 1fr;
      }

      .timeline time {
        color: var(--muted);
        font-size: 13px;
        font-weight: 700;
      }

      @media (max-width: 1000px) {
        .stats {
          grid-template-columns: repeat(2, minmax(0, 1fr));
        }

        .dashboard-grid {
          grid-template-columns: 1fr;
        }
      }

      @media (max-width: 720px) {
        .hero {
          align-items: start;
          flex-direction: column;
        }

        .stats,
        .mission-row {
          grid-template-columns: 1fr;
        }

        .progress-block > span {
          text-align: left;
        }
      }
    `,
  ],
})
export class DashboardPageComponent {}

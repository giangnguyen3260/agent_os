import {Component} from '@angular/core';

@Component({
  selector: 'app-mission-list-page',
  standalone: true,
  template: `
    <div class="page">
      <section class="page-heading">
        <div>
          <span class="eyebrow">Mission Queue</span>
          <h1>Missions</h1>
          <p class="muted">
            Create goals, inspect task graphs, and follow execution state.
          </p>
        </div>
        <button type="button" class="button">Create mission</button>
      </section>

      <section class="mission-board">
        <article class="panel mission-card">
          <div class="mission-header">
            <div>
              <h2>Build customer registration API</h2>
              <p class="muted">Engineering · Manager Maya · Backend Alex</p>
            </div>
            <span class="pill">Running</span>
          </div>
          <div class="task-flow">
            <span class="done">Analyze</span>
            <span class="done">Design</span>
            <span class="active">Implement</span>
            <span>QA</span>
            <span>Review</span>
          </div>
          <div class="mission-footer">
            <span>Cost $12.40 / $20</span>
            <span>3 model calls · 1 tool call</span>
          </div>
        </article>

        <article class="panel mission-card">
          <div class="mission-header">
            <div>
              <h2>Design billing service boundary</h2>
              <p class="muted">Architecture · Emma</p>
            </div>
            <span class="pill">Review</span>
          </div>
          <div class="task-flow">
            <span class="done">Scope</span>
            <span class="done">Context</span>
            <span class="done">ADR</span>
            <span class="active">Human review</span>
          </div>
          <div class="mission-footer">
            <span>Cost $8.10 / $15</span>
            <span>5 model calls · 0 tool calls</span>
          </div>
        </article>
      </section>
    </div>
  `,
  styles: [
    `
      h1,
      h2 {
        margin: 0 0 8px;
      }

      .page-heading,
      .mission-header,
      .mission-footer {
        align-items: center;
        display: flex;
        gap: 16px;
        justify-content: space-between;
      }

      .mission-board {
        display: grid;
        gap: 16px;
      }

      .mission-card {
        display: grid;
        gap: 18px;
      }

      .task-flow {
        display: flex;
        flex-wrap: wrap;
        gap: 10px;
      }

      .task-flow span {
        background: #eef2f7;
        border: 1px solid var(--line);
        border-radius: 999px;
        color: #3b465a;
        font-size: 13px;
        font-weight: 700;
        padding: 7px 10px;
      }

      .task-flow .done {
        background: #ecfdf3;
        border-color: #b8e6c8;
        color: #11643f;
      }

      .task-flow .active {
        background: #eef4ff;
        border-color: #c7d7fe;
        color: #174ea6;
      }

      .mission-footer {
        border-top: 1px solid var(--line);
        color: var(--muted);
        font-size: 13px;
        padding-top: 14px;
      }
    `,
  ],
})
export class MissionListPageComponent {}

import {Component} from '@angular/core';

@Component({
  selector: 'app-approval-list-page',
  standalone: true,
  template: `
    <div class="page">
      <section>
        <h1>Approvals</h1>
        <p class="muted">
          Review database changes, production actions, cost escalations, and
          risky tool calls.
        </p>
      </section>

      <section class="panel">
        <h2>Pending Approval Requests</h2>
        <p class="muted">Nothing is waiting for approval.</p>
      </section>
    </div>
  `,
})
export class ApprovalListPageComponent {}

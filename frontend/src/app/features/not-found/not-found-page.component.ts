import {Component} from '@angular/core';
import {RouterLink} from '@angular/router';

@Component({
  selector: 'app-not-found-page',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="page panel" aria-labelledby="not-found-title">
      <span class="eyebrow">404</span>
      <h1 id="not-found-title">Page not found</h1>
      <p class="muted">This address does not match an AgentOS page.</p>
      <div><a class="button" routerLink="/dashboard">Go to dashboard</a></div>
    </section>
  `,
})
export class NotFoundPageComponent {}

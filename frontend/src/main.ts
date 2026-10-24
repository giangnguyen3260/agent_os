import {bootstrapApplication} from '@angular/platform-browser';
import {provideRouter, withRouterConfig} from '@angular/router';
import {provideHttpClient} from '@angular/common/http';
import {AppComponent} from './app/app.component';
import {routes} from './app/app.routes';

bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(
      routes,
      // Restore the history position after a dirty form rejects Back/Forward.
      withRouterConfig({canceledNavigationResolution: 'computed'}),
    ),
    provideHttpClient(),
  ],
}).catch(error => console.error(error));

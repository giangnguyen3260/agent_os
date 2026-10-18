import {Component} from '@angular/core';
import {RouterOutlet} from '@angular/router';
import {ShellLayoutComponent} from './core/layout/shell-layout.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, ShellLayoutComponent],
  template: '<app-shell-layout><router-outlet /></app-shell-layout>',
})
export class AppComponent {}

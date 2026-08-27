import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/theme.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  title = 'frontend';

  // Injected (not just provided) so the theme class is applied to <html> immediately on bootstrap,
  // before any route renders - otherwise a dark-mode user would see a flash of the light theme.
  constructor(private themeService: ThemeService) {}
}

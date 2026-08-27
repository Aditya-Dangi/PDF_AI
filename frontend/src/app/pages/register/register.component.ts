import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ThemeToggleComponent } from '../../components/theme-toggle/theme-toggle.component';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, ThemeToggleComponent],
  templateUrl: './register.component.html'
})
export class RegisterComponent {
  email = '';
  password = '';
  error = signal<string | null>(null);
  loading = signal(false);

  constructor(private authService: AuthService, private router: Router) {}

  submit(): void {
    this.error.set(null);
    this.loading.set(true);
    this.authService.register(this.email, this.password).subscribe({
      next: () => {
        this.loading.set(false);
        this.router.navigate(['/documents']);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? 'Registration failed.');
      }
    });
  }
}

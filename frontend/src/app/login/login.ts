import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ThemeToggle } from '../shared/theme-toggle/theme-toggle';
import { ApiService } from '../core/api.service';
import { errorText } from '../core/error-text';

@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink, ThemeToggle],
  templateUrl: './login.html'
})
export class Login {
  private api = inject(ApiService);
  private router = inject(Router);

  mobile = '';
  password = '';
  readonly busy = signal(false);
  readonly error = signal('');

  /** Set when the auth interceptor sent us here after a 401, so the redirect is explained
   *  rather than just happening. Tokens last two hours and there is no refresh. */
  readonly expired = signal(new URLSearchParams(location.search).has('expired'));

  submit() {
    this.error.set('');
    if (!this.mobile.trim() || !this.password) {
      this.error.set('Enter your mobile and password.');
      return;
    }
    this.busy.set(true);
    this.api.login(this.mobile.trim(), this.password).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      error: e => {
        this.error.set(errorText(e, 'Sign in failed'));
        this.busy.set(false);
      }
    });
  }
}

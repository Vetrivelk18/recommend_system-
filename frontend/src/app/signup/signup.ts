import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ThemeToggle } from '../shared/theme-toggle/theme-toggle';
import { ApiService } from '../core/api.service';
import { errorText } from '../core/error-text';
import { OnboardingOptions } from '../core/models';

@Component({
  selector: 'app-signup',
  imports: [FormsModule, RouterLink, ThemeToggle],
  templateUrl: './signup.html'
})
export class Signup {
  private api = inject(ApiService);
  private router = inject(Router);

  readonly step = signal<1 | 2>(1);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly options = signal<OnboardingOptions | null>(null);

  // step 1
  name = '';
  mobile = '';
  password = '';

  // step 2
  department = '';
  aisle = '';
  dow = '';
  hour = '';
  organic = '';

  /**
   * 134 aisles is a lot to thumb through, so let people narrow the list.
   * Must be a signal: computed() only re-evaluates when a signal it reads
   * changes, so a plain field here would never trigger a recompute.
   */
  readonly aisleFilter = signal('');
  readonly visibleAisles = computed(() => {
    const all = this.options()?.aisles ?? [];
    const q = this.aisleFilter().trim().toLowerCase();
    return q ? all.filter(a => a.name.toLowerCase().includes(q)) : all;
  });

  next() {
    this.error.set('');
    if (!this.name.trim()) return this.error.set('Name is required.');
    if (!/^\d{10,15}$/.test(this.mobile.trim()))
      return this.error.set('Mobile must be 10 to 15 digits.');
    if (this.password.length < 8)
      return this.error.set('Password must be at least 8 characters.');

    this.step.set(2);
    if (!this.options()) this.loadOptions();
  }

  /**
   * Filtering can hide the currently selected aisle, which leaves the select
   * blank while the model still holds the old value - so reconcile to the first
   * visible option whenever the current one drops out of view.
   */
  onAisleFilter(value: string) {
    this.aisleFilter.set(value);
    const visible = this.visibleAisles();
    if (visible.length && !visible.some(a => a.name === this.aisle)) {
      this.aisle = visible[0].name;
    }
  }

  back() {
    this.error.set('');
    this.step.set(1);
  }

  private loadOptions() {
    this.busy.set(true);
    this.api.onboardingOptions().subscribe({
      next: o => {
        this.options.set(o);
        this.department = o.departments[0]?.name ?? '';
        this.aisle = o.aisles[0]?.name ?? '';
        this.dow = o.days[0] ?? '';
        this.hour = o.times[0] ?? '';
        this.organic = o.organic[2]?.value ?? o.organic[0]?.value ?? '';
        this.busy.set(false);
      },
      error: e => {
        this.error.set(errorText(e, 'Could not load the questions'));
        this.busy.set(false);
      }
    });
  }

  submit() {
    this.error.set('');
    this.busy.set(true);
    this.api.signup({
      name: this.name.trim(),
      mobile: this.mobile.trim(),
      password: this.password,
      preferences: {
        DEPARTMENT: this.department,
        AISLE: this.aisle,
        DOW: this.dow,
        HOUR: this.hour,
        ORGANIC: this.organic
      }
    }).subscribe({
      next: () => this.router.navigate(['/dashboard']),
      error: e => {
        this.error.set(errorText(e, 'Signup failed'));
        this.busy.set(false);
      }
    });
  }
}

import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ThemeToggle } from '../shared/theme-toggle/theme-toggle';
import { ApiService } from '../core/api.service';
import { errorText } from '../core/error-text';
import { DashboardResponse } from '../core/models';

@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, ThemeToggle],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css'
})
export class Dashboard {
  private api = inject(ApiService);
  private router = inject(Router);

  readonly data = signal<DashboardResponse | null>(null);
  readonly error = signal('');

  readonly recos = signal<string[] | null>(null);
  readonly recosLoading = signal(false);
  readonly recosError = signal('');
  /** Round-trip time of the last load - the only way a cache hit is visible from here. */
  readonly recosMs = signal<number | null>(null);

  /** Matches RAIL_SIZE on the server, so the skeleton is the right shape. */
  readonly placeholders = [1, 2, 3, 4, 5];

  readonly cartCount = this.api.cartCount;

  /**
   * Stable colour per product name - a cheap visual anchor so a rail of text
   * rows is scannable. Hue only, so it stays legible in both themes.
   */
  tint(name: string): string {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
    return `hsl(${h} 42% 42%)`;
  }

  constructor() {
    const id = this.api.userId();
    if (id === null) {
      this.router.navigate(['/login']);
      return;
    }
    this.api.dashboard(id).subscribe({
      next: d => this.data.set(d),
      error: e => this.error.set(errorText(e, 'Could not load your dashboard'))
    });

    // One extra indexed query, so the Cart badge is correct on first paint rather than
    // only after the user visits the cart. A failure here is not worth surfacing.
    this.api.cart(id).subscribe({ error: () => {} });
  }

  loadRecommendations() {
    const id = this.api.userId();
    if (id === null || this.recosLoading()) {
      return;
    }

    this.recosLoading.set(true);
    this.recosError.set('');
    const started = performance.now();

    this.api.recommendations(id).subscribe({
      next: r => {
        this.recosMs.set(Math.round(performance.now() - started));
        this.recos.set(r);
        this.recosLoading.set(false);
      },
      error: e => {
        this.recosMs.set(Math.round(performance.now() - started));
        this.recosError.set(errorText(e, 'Could not load recommendations'));
        this.recosLoading.set(false);
      }
    });
  }

  signOut() {
    this.api.signOut();
    this.router.navigate(['/login']);
  }
}

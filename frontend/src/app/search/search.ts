import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ThemeToggle } from '../shared/theme-toggle/theme-toggle';
import { ApiService } from '../core/api.service';
import { errorText } from '../core/error-text';
import { SearchResult } from '../core/models';

@Component({
  selector: 'app-search',
  imports: [FormsModule, RouterLink, ThemeToggle],
  templateUrl: './search.html',
  styleUrl: './search.css'
})
export class Search {
  private api = inject(ApiService);
  private router = inject(Router);

  readonly query = signal('');
  readonly results = signal<SearchResult[] | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly stored = signal(false);
  readonly userId = this.api.userId();

  /** Mirrors ApiService so the header badge is reactive here too. */
  readonly cartCount = this.api.cartCount;

  /** Product ids currently mid-request, and ones already added this visit. */
  readonly adding = signal<number | null>(null);
  readonly added = signal<number[]>([]);

  /** Same colour-per-name trick as the dashboard rails, for a consistent look. */
  tint(name: string): string {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
    return `hsl(${h} 42% 42%)`;
  }

  search() {
    const q = this.query().trim();
    if (!q) return;

    this.loading.set(true);
    this.error.set('');
    this.stored.set(false);

    // userId is optional server-side, but without it nothing is written to
    // last_search - there would be no query arm for the webhook job to read later.
    // Surfaced as `stored` so this page also doubles as proof the write happened.
    this.api.search(q, this.userId, 10).subscribe({
      next: r => {
        this.results.set(r);
        this.stored.set(this.userId !== null && r.length > 0);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(errorText(e, 'Search failed'));
        this.results.set(null);
        this.loading.set(false);
      }
    });
  }

  /**
   * Search is the only rail that carries product ids - the dashboard rails return names
   * only (DashboardResponse), so this is the one place an add can originate today.
   */
  add(productId: number) {
    if (this.userId === null || this.adding() !== null) return;

    this.adding.set(productId);
    this.api.addToCart(this.userId, productId, 1).subscribe({
      next: () => {
        this.added.update(ids => ids.includes(productId) ? ids : [...ids, productId]);
        this.adding.set(null);
      },
      error: e => {
        this.error.set(errorText(e, 'Could not add that to your cart'));
        this.adding.set(null);
      }
    });
  }

  isAdded(productId: number): boolean {
    return this.added().includes(productId);
  }

  back() {
    this.router.navigate(['/dashboard']);
  }
}

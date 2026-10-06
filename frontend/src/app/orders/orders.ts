import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ThemeToggle } from '../shared/theme-toggle/theme-toggle';
import { ApiService } from '../core/api.service';
import { errorText } from '../core/error-text';
import { OrderDetail, OrderSummary } from '../core/models';

@Component({
  selector: 'app-orders',
  imports: [RouterLink, ThemeToggle],
  templateUrl: './orders.html',
  styleUrl: './orders.css'
})
export class Orders {
  private api = inject(ApiService);
  private router = inject(Router);

  readonly orders = signal<OrderSummary[] | null>(null);
  readonly error = signal('');

  /** Lines are fetched per order on expand, so the list itself stays one query. */
  readonly open = signal<number | null>(null);
  readonly detail = signal<OrderDetail | null>(null);
  readonly detailLoading = signal(false);

  /** Set when we arrived straight from a successful checkout, to confirm it landed. */
  readonly justPlaced = signal<number | null>(null);

  readonly userId = this.api.userId();

  constructor() {
    if (this.userId === null) {
      this.router.navigate(['/login']);
      return;
    }

    const placed = Number(new URLSearchParams(location.search).get('placed'));
    if (placed) {
      this.justPlaced.set(placed);
    }

    this.api.orders(this.userId).subscribe({
      next: o => {
        this.orders.set(o);
        if (placed) this.toggle(placed);
      },
      error: e => this.error.set(errorText(e, 'Could not load your orders'))
    });
  }

  toggle(orderId: number) {
    if (this.userId === null) return;

    if (this.open() === orderId) {
      this.open.set(null);
      this.detail.set(null);
      return;
    }

    this.open.set(orderId);
    this.detail.set(null);
    this.detailLoading.set(true);

    this.api.order(this.userId, orderId).subscribe({
      next: d => { this.detail.set(d); this.detailLoading.set(false); },
      error: e => { this.error.set(errorText(e, 'Could not load that order')); this.detailLoading.set(false); }
    });
  }

  when(iso: string): string {
    return new Date(iso).toLocaleString(undefined, {
      day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit'
    });
  }

  back() {
    this.router.navigate(['/dashboard']);
  }
}

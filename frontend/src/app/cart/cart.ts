import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ThemeToggle } from '../shared/theme-toggle/theme-toggle';
import { ApiService } from '../core/api.service';
import { errorText } from '../core/error-text';
import { CartView } from '../core/models';

@Component({
  selector: 'app-cart',
  imports: [RouterLink, ThemeToggle],
  templateUrl: './cart.html',
  styleUrl: './cart.css'
})
export class Cart {
  private api = inject(ApiService);
  private router = inject(Router);

  readonly cart = signal<CartView | null>(null);
  readonly error = signal('');
  readonly placing = signal(false);

  /** Product names the server refused at checkout, so the offending lines can be marked. */
  readonly blocked = signal<string[]>([]);

  /** Per-line guard, so two quick taps on one row cannot overlap. */
  readonly busy = signal<number | null>(null);

  readonly userId = this.api.userId();

  tint(name: string): string {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
    return `hsl(${h} 42% 42%)`;
  }

  constructor() {
    if (this.userId === null) {
      this.router.navigate(['/login']);
      return;
    }
    this.api.cart(this.userId).subscribe({
      next: c => this.cart.set(c),
      error: e => this.error.set(errorText(e, 'Could not load your cart'))
    });
  }

  /** Delta of +1 or -1. Reaching zero removes the line server-side. */
  step(productId: number, by: number) {
    const current = this.cart()?.lines.find(l => l.productId === productId);
    if (!current || this.userId === null || this.busy() !== null) return;

    const next = current.quantity + by;
    if (next > 99) return;

    this.busy.set(productId);
    this.api.setCartQuantity(this.userId, productId, next).subscribe({
      next: c => { this.cart.set(c); this.busy.set(null); },
      error: e => { this.error.set(errorText(e, 'Could not update that line')); this.busy.set(null); }
    });
  }

  remove(productId: number) {
    if (this.userId === null || this.busy() !== null) return;

    this.busy.set(productId);
    this.api.removeFromCart(this.userId, productId).subscribe({
      next: c => { this.cart.set(c); this.busy.set(null); },
      error: e => { this.error.set(errorText(e, 'Could not remove that line')); this.busy.set(null); }
    });
  }

  placeOrder() {
    if (this.userId === null || this.placing()) return;

    this.placing.set(true);
    this.error.set('');
    this.blocked.set([]);

    this.api.placeOrder(this.userId).subscribe({
      next: detail => {
        this.placing.set(false);
        this.router.navigate(['/orders'], { queryParams: { placed: detail.order.orderId } });
      },
      error: e => {
        this.placing.set(false);
        // A 409 carrying `products` means specific lines are out of stock, not that the
        // whole checkout is malformed - mark them rather than just showing the message.
        this.blocked.set(e?.error?.products ?? []);
        this.error.set(errorText(e, 'Could not place your order'));
      }
    });
  }

  isBlocked(product: string): boolean {
    return this.blocked().includes(product);
  }

  back() {
    this.router.navigate(['/dashboard']);
  }
}

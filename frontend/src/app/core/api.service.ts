import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { Router } from '@angular/router';
import { catchError, forkJoin, map, tap, throwError } from 'rxjs';
import {
  AuthResponse, CartView, DashboardResponse, OnboardingOptions, OrderDetail,
  OrderSummary, Option, OrganicOption, SearchResult, SignupRequest
} from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  /** Survives a reload; cleared on sign out. */
  readonly userId = signal<number | null>(this.read('userId'));
  readonly userName = signal<string | null>(this.read('userName'));

  login(mobile: string, password: string) {
    return this.http
      .post<AuthResponse>('/api/auth/login', { mobile, password })
      .pipe(tap(r => this.remember(r)));
  }

  signup(body: SignupRequest) {
    return this.http
      .post<AuthResponse>('/api/auth/signup', body)
      .pipe(tap(r => this.remember(r)));
  }

  /**
   * The five dropdown endpoints in parallel. One round trip's worth of latency
   * instead of five, which matters on a phone connection.
   */
  onboardingOptions() {
    return forkJoin({
      departments: this.http.get<Option[]>('/api/onboarding/departments'),
      aisles: this.http.get<Option[]>('/api/onboarding/aisles'),
      days: this.http.get<string[]>('/api/onboarding/days'),
      times: this.http.get<string[]>('/api/onboarding/times'),
      organic: this.http.get<OrganicOption[]>('/api/onboarding/organic')
    }).pipe(map(o => o as OnboardingOptions));
  }

  dashboard(userId: number) {
    return this.http.get<DashboardResponse>(`/api/dashboard/${userId}`);
  }

  /**
   * Hits the real RRF (semantic text_emb + lexical name_tsv), and - when userId is
   * given - this call is also what populates last_search for that user, which the
   * webhook job reads 15 minutes later. There is no separate "confirm" step; issuing
   * the search IS the write.
   */
  search(q: string, userId: number | null, limit = 10) {
    let params = `q=${encodeURIComponent(q)}&limit=${limit}`;
    if (userId !== null) params += `&userId=${userId}`;
    return this.http.get<SearchResult[]>(`/api/search?${params}`);
  }

  /**
   * The same picks the webhook texts, off the same Redis entry. A warm read returns in
   * milliseconds; a cold one reruns the reranker and can take the better part of a
   * minute, which is why the dashboard asks for these on demand rather than on load.
   */
  recommendations(userId: number) {
    return this.http.get<string[]>(`/api/recommendations/${userId}`);
  }

  /**
   * Distinct products in the cart, for the header badge. Kept as a signal so every cart
   * call can refresh it in passing - the server returns the whole cart from each mutating
   * endpoint, so nothing here needs a follow-up GET.
   */
  readonly cartCount = signal(0);

  cart(userId: number) {
    return this.http.get<CartView>(`/api/cart/${userId}`).pipe(tap(c => this.countFrom(c)));
  }

  addToCart(userId: number, productId: number, quantity = 1) {
    return this.http
      .post<CartView>(`/api/cart/${userId}/items`, { productId, quantity })
      .pipe(tap(c => this.countFrom(c)));
  }

  /** An absolute quantity. Zero removes the line. */
  setCartQuantity(userId: number, productId: number, quantity: number) {
    return this.http
      .patch<CartView>(`/api/cart/${userId}/items/${productId}`, { quantity })
      .pipe(tap(c => this.countFrom(c)));
  }

  removeFromCart(userId: number, productId: number) {
    return this.http
      .delete<CartView>(`/api/cart/${userId}/items/${productId}`)
      .pipe(tap(c => this.countFrom(c)));
  }

  clearCart(userId: number) {
    return this.http.delete<CartView>(`/api/cart/${userId}`).pipe(tap(c => this.countFrom(c)));
  }

  /**
   * The cart is the request body - there is nothing to send. Succeeds with the placed
   * order, or 409 when the cart is empty or holds something out of stock.
   */
  placeOrder(userId: number) {
    return this.http
      .post<OrderDetail>(`/api/orders/${userId}`, {})
      .pipe(tap(() => this.cartCount.set(0)));
  }

  orders(userId: number) {
    return this.http.get<OrderSummary[]>(`/api/orders/${userId}`);
  }

  order(userId: number, orderId: number) {
    return this.http.get<OrderDetail>(`/api/orders/${userId}/${orderId}`);
  }

  private countFrom(cart: CartView) {
    this.cartCount.set(cart.lineCount);
  }

  signOut() {
    sessionStorage.clear();
    this.userId.set(null);
    this.userName.set(null);
    this.cartCount.set(0);
  }

  private remember(r: AuthResponse) {
    sessionStorage.setItem('token', r.token);
    sessionStorage.setItem('userId', String(r.userId));
    sessionStorage.setItem('userName', r.name);
    this.userId.set(r.userId);
    this.userName.set(r.name);
  }

  private read(key: string) {
    const v = sessionStorage.getItem(key);
    return v === null ? null : (key === 'userId' ? Number(v) : v) as never;
  }
}

/**
 * Attaches the stored JWT to every outgoing request.
 *
 * <p>The token was already being kept in sessionStorage by remember() above, but nothing
 * ever sent it. The server's RateLimitFilter reads it to count per user rather than per
 * IP - without this, every browser behind one address shares a single bucket.
 *
 * <p>Read straight from sessionStorage rather than from the ApiService signal: an
 * interceptor is created before the service in the injector, and this keeps the two
 * independent. Requests to other origins are left alone.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const token = sessionStorage.getItem('token');

  const outgoing = token && req.url.startsWith('/api/')
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(outgoing).pipe(
    catchError(error => {
      // 401 means the token is missing, expired or bad - the session is over, and with a
      // 120-minute expiry and no refresh that is a normal end to a visit, not a fault.
      // Without this the pages just render "(401)" and leave the user stranded.
      //
      // 403 is deliberately NOT handled here: the credentials are fine and the request was
      // not, so signing the user out would hide a bug rather than fix anything.
      if (error?.status === 401 && !req.url.includes('/api/auth/')) {
        sessionStorage.clear();
        router.navigate(['/login'], { queryParams: { expired: 1 } });
      }
      return throwError(() => error);
    })
  );
};

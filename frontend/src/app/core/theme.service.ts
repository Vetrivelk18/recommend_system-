import { Injectable, effect, signal } from '@angular/core';

export type Theme = 'light' | 'dark' | 'system';

const KEY = 'theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<Theme>(this.restore());

  constructor() {
    // data-theme on <html> lets an explicit choice beat prefers-color-scheme
    // in both directions; 'system' removes it and the media query takes over.
    effect(() => {
      const t = this.theme();
      const root = document.documentElement;
      if (t === 'system') {
        root.removeAttribute('data-theme');
      } else {
        root.setAttribute('data-theme', t);
      }
      try { localStorage.setItem(KEY, t); } catch { /* private mode */ }
    });
  }

  cycle() {
    const order: Theme[] = ['system', 'light', 'dark'];
    this.theme.set(order[(order.indexOf(this.theme()) + 1) % order.length]);
  }

  private restore(): Theme {
    try {
      const v = localStorage.getItem(KEY);
      if (v === 'light' || v === 'dark' || v === 'system') return v;
    } catch { /* private mode */ }
    return 'system';
  }
}

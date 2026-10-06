import { Component, computed, inject } from '@angular/core';
import { ThemeService } from '../../core/theme.service';

@Component({
  selector: 'app-theme-toggle',
  template: `
    <button type="button" class="theme-toggle" (click)="theme.cycle()"
            [attr.aria-label]="'Theme: ' + theme.theme() + '. Click to change.'"
            [title]="'Theme: ' + theme.theme()">
      <span aria-hidden="true">{{ icon() }}</span>
    </button>
  `,
  styles: [`
    .theme-toggle {
      width: 40px; height: 40px; min-height: 40px; padding: 0;
      display: grid; place-items: center;
      font-size: 1rem; line-height: 1;
      color: var(--ink); background: var(--surface-2);
      border: 1px solid var(--line); border-radius: 50%;
    }
    .theme-toggle:hover { background: var(--surface-3); }
  `]
})
export class ThemeToggle {
  readonly theme = inject(ThemeService);
  readonly icon = computed(() =>
    ({ system: '◐', light: '☀', dark: '☾' })[this.theme.theme()]);
}

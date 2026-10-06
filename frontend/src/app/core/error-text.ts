import { HttpErrorResponse } from '@angular/common/http';

/** Spring returns {"error": "..."} for our own failures and a Boot envelope for
 *  bean-validation ones, which carries no useful field detail. */
export function errorText(e: unknown, fallback: string): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return 'Cannot reach the server. Is it running on :8080?';
    if (e.error?.error) return e.error.error;
    if (e.status === 400) return 'Please check the values and try again.';
    return `${fallback} (${e.status})`;
  }
  return fallback;
}

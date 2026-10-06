import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./login/login').then(m => m.Login) },
  { path: 'signup', loadComponent: () => import('./signup/signup').then(m => m.Signup) },
  { path: 'dashboard', loadComponent: () => import('./dashboard/dashboard').then(m => m.Dashboard) },
  { path: 'search', loadComponent: () => import('./search/search').then(m => m.Search) },
  { path: 'cart', loadComponent: () => import('./cart/cart').then(m => m.Cart) },
  { path: 'orders', loadComponent: () => import('./orders/orders').then(m => m.Orders) },
  { path: '', pathMatch: 'full', redirectTo: 'login' },
  { path: '**', redirectTo: 'login' }
];

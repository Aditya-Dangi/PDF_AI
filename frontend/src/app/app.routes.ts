import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  {
    // Public landing page. Deliberately not behind authGuard and not redirected for signed-in
    // users - the nav and calls to action switch to "Open app" instead, so the marketing page
    // stays reachable rather than becoming unvisitable the moment someone has an account.
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./pages/landing/landing.component').then((m) => m.LandingComponent)
  },
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'register',
    loadComponent: () => import('./pages/register/register.component').then((m) => m.RegisterComponent)
  },
  {
    path: 'documents',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./pages/document-list/document-list.component').then((m) => m.DocumentListComponent)
  },
  {
    path: 'documents/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/workspace/workspace.component').then((m) => m.WorkspaceComponent)
  },
  { path: '**', redirectTo: '' }
];

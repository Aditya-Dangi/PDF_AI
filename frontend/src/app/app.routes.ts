import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'documents', pathMatch: 'full' },
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
  { path: '**', redirectTo: 'documents' }
];

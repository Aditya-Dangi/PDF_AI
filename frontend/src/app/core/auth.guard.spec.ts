import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { authGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('authGuard', () => {
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let routerSpy: jasmine.SpyObj<Router>;

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['isAuthenticated']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);

    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authServiceSpy },
        { provide: Router, useValue: routerSpy }
      ]
    });
  });

  function runGuard(): boolean {
    return TestBed.runInInjectionContext(() => authGuard({} as any, {} as any)) as boolean;
  }

  it('allows navigation when the user is authenticated', () => {
    authServiceSpy.isAuthenticated.and.returnValue(true);

    expect(runGuard()).toBeTrue();
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });

  it('blocks navigation and redirects to /login when the user is not authenticated', () => {
    authServiceSpy.isAuthenticated.and.returnValue(false);

    expect(runGuard()).toBeFalse();
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/login']);
  });
});

import { TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';
import { API_BASE_URL } from './api-config';
import { AuthResponse } from './models';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let router: jasmine.SpyObj<Router>;

  const sampleResponse: AuthResponse = {
    token: 'fake-jwt-token',
    userId: 'user-123',
    email: 'person@example.com'
  };

  beforeEach(() => {
    localStorage.clear();
    const routerSpy = jasmine.createSpyObj('Router', ['navigate']);

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [{ provide: Router, useValue: routerSpy }]
    });

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router) as jasmine.SpyObj<Router>;
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('is not authenticated when no token is stored', () => {
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.getToken()).toBeNull();
  });

  it('stores the token and email, and becomes authenticated, after a successful login', () => {
    service.login('person@example.com', 'password123').subscribe();

    const req = httpMock.expectOne(`${API_BASE_URL}/auth/login`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'person@example.com', password: 'password123' });
    req.flush(sampleResponse);

    expect(service.isAuthenticated()).toBeTrue();
    expect(service.getToken()).toBe('fake-jwt-token');
    expect(localStorage.getItem('fc_email')).toBe('person@example.com');
    expect(service.currentEmail()).toBe('person@example.com');
  });

  it('stores the session after a successful registration too', () => {
    service.register('new@example.com', 'password123').subscribe();

    const req = httpMock.expectOne(`${API_BASE_URL}/auth/register`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...sampleResponse, email: 'new@example.com' });

    expect(service.isAuthenticated()).toBeTrue();
    expect(service.currentEmail()).toBe('new@example.com');
  });

  it('does not store a session when login fails', () => {
    service.login('person@example.com', 'wrong-password').subscribe({
      error: () => {}
    });

    const req = httpMock.expectOne(`${API_BASE_URL}/auth/login`);
    req.flush({ message: 'Invalid email or password' }, { status: 401, statusText: 'Unauthorized' });

    expect(service.isAuthenticated()).toBeFalse();
    expect(service.getToken()).toBeNull();
  });

  it('clears the stored session and navigates to /login on logout', () => {
    localStorage.setItem('fc_token', 'fake-jwt-token');
    localStorage.setItem('fc_email', 'person@example.com');

    service.logout();

    expect(service.getToken()).toBeNull();
    expect(localStorage.getItem('fc_email')).toBeNull();
    expect(service.currentEmail()).toBeNull();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  it('picks up an already-stored email when the service is constructed', () => {
    localStorage.setItem('fc_email', 'returning-user@example.com');

    const freshService = TestBed.runInInjectionContext(
      () => new AuthService(TestBed.inject(HttpClient), TestBed.inject(Router))
    );

    expect(freshService.currentEmail()).toBe('returning-user@example.com');
  });
});

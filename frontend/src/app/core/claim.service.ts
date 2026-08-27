import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-config';
import { AuditResponse, Claim } from './models';

@Injectable({ providedIn: 'root' })
export class ClaimService {
  constructor(private http: HttpClient) {}

  decomposeMessage(documentId: string, messageId: string): Observable<Claim[]> {
    return this.http.post<Claim[]>(`${API_BASE_URL}/documents/${documentId}/claims/decompose`, { messageId });
  }

  decomposeClaimText(documentId: string, claimText: string): Observable<Claim[]> {
    return this.http.post<Claim[]>(`${API_BASE_URL}/documents/${documentId}/claims/decompose`, { claimText });
  }

  listClaims(documentId: string): Observable<Claim[]> {
    return this.http.get<Claim[]>(`${API_BASE_URL}/documents/${documentId}/claims`);
  }

  challenge(documentId: string, claimId: string): Observable<Claim> {
    return this.http.post<Claim>(`${API_BASE_URL}/documents/${documentId}/claims/${claimId}/challenge`, {});
  }

  startAudit(documentId: string): Observable<AuditResponse> {
    return this.http.post<AuditResponse>(`${API_BASE_URL}/documents/${documentId}/audit`, {});
  }

  getAuditStatus(documentId: string): Observable<AuditResponse> {
    return this.http.get<AuditResponse>(`${API_BASE_URL}/documents/${documentId}/audit`);
  }
}

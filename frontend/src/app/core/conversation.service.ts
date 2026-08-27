import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-config';
import { AnswerResponse, ChatMessage, FactCheckResponse } from './models';

@Injectable({ providedIn: 'root' })
export class ConversationService {
  constructor(private http: HttpClient) {}

  ask(documentId: string, question: string): Observable<AnswerResponse> {
    return this.http.post<AnswerResponse>(`${API_BASE_URL}/documents/${documentId}/ask`, { question });
  }

  factCheckMessage(documentId: string, messageId: string): Observable<FactCheckResponse> {
    return this.http.post<FactCheckResponse>(`${API_BASE_URL}/documents/${documentId}/fact-check`, { messageId });
  }

  factCheckClaim(documentId: string, claimText: string): Observable<FactCheckResponse> {
    return this.http.post<FactCheckResponse>(`${API_BASE_URL}/documents/${documentId}/fact-check`, { claimText });
  }

  history(documentId: string): Observable<ChatMessage[]> {
    return this.http.get<ChatMessage[]>(`${API_BASE_URL}/documents/${documentId}/messages`);
  }
}

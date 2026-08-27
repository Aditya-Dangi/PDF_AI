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

  /** Same as ask(), for a dragged image region (a diagram/chart the text layer can't cover)
   *  instead of a typed question - the backend OCRs it (falling back to a vision-model
   *  description) and resolves it to text before running the exact same grounded-QA flow. */
  askImage(documentId: string, imageDataUrl: string): Observable<AnswerResponse> {
    const formData = new FormData();
    formData.append('image', dataUrlToBlob(imageDataUrl), 'region.png');
    return this.http.post<AnswerResponse>(`${API_BASE_URL}/documents/${documentId}/ask-image`, formData);
  }

  /** Same as factCheckClaim(), for a dragged image region. */
  factCheckImage(documentId: string, imageDataUrl: string): Observable<FactCheckResponse> {
    const formData = new FormData();
    formData.append('image', dataUrlToBlob(imageDataUrl), 'region.png');
    return this.http.post<FactCheckResponse>(`${API_BASE_URL}/documents/${documentId}/fact-check-image`, formData);
  }

  history(documentId: string): Observable<ChatMessage[]> {
    return this.http.get<ChatMessage[]>(`${API_BASE_URL}/documents/${documentId}/messages`);
  }
}

function dataUrlToBlob(dataUrl: string): Blob {
  const [header, base64] = dataUrl.split(',');
  const mime = /:(.*?);/.exec(header)?.[1] ?? 'image/png';
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new Blob([bytes], { type: mime });
}

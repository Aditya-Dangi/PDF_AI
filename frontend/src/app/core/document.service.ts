import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-config';
import { DocumentSummary } from './models';

@Injectable({ providedIn: 'root' })
export class DocumentService {
  constructor(private http: HttpClient) {}

  list(): Observable<DocumentSummary[]> {
    return this.http.get<DocumentSummary[]>(`${API_BASE_URL}/documents`);
  }

  get(id: string): Observable<DocumentSummary> {
    return this.http.get<DocumentSummary>(`${API_BASE_URL}/documents/${id}`);
  }

  upload(file: File): Observable<DocumentSummary> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<DocumentSummary>(`${API_BASE_URL}/documents`, formData);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${API_BASE_URL}/documents/${id}`);
  }

  async fetchFileBlobUrl(id: string): Promise<string> {
    const token = localStorage.getItem('fc_token');
    const response = await fetch(`${API_BASE_URL}/documents/${id}/file`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {}
    });
    if (!response.ok) throw new Error('Failed to load PDF file');
    const blob = await response.blob();
    return URL.createObjectURL(blob);
  }
}

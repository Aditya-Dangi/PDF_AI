import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './api-config';
import { StructureResponse } from './models';

@Injectable({ providedIn: 'root' })
export class StructureService {
  constructor(private http: HttpClient) {}

  /** The backend computes this on first call and caches it, so the first request for a large
   *  document is noticeably slower than later ones - the caller should show a loading state. */
  getStructure(documentId: string): Observable<StructureResponse> {
    return this.http.get<StructureResponse>(`${API_BASE_URL}/documents/${documentId}/structure`);
  }
}

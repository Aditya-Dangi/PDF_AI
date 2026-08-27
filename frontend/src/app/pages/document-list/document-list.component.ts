import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { DocumentService } from '../../core/document.service';
import { AuthService } from '../../core/auth.service';
import { DocumentSummary } from '../../core/models';
import { LoadingTimerComponent } from '../../components/loading-timer/loading-timer.component';
import { ThemeToggleComponent } from '../../components/theme-toggle/theme-toggle.component';
import { TIME_ESTIMATES, estimateProcessingSeconds } from '../../core/time-estimates';

@Component({
  selector: 'app-document-list',
  standalone: true,
  imports: [CommonModule, LoadingTimerComponent, ThemeToggleComponent],
  templateUrl: './document-list.component.html'
})
export class DocumentListComponent implements OnInit, OnDestroy {
  readonly uploadEstimateSeconds = TIME_ESTIMATES.upload;

  documents = signal<DocumentSummary[]>([]);
  uploading = signal(false);
  error = signal<string | null>(null);
  dragging = signal(false);

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  constructor(
    private documentService: DocumentService,
    private authService: AuthService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.refresh();
    this.pollHandle = setInterval(() => this.refresh(true), 4000);
  }

  ngOnDestroy(): void {
    if (this.pollHandle) clearInterval(this.pollHandle);
  }

  refresh(silent = false): void {
    this.documentService.list().subscribe({
      next: (docs) => this.documents.set(docs),
      error: (err) => {
        if (!silent) this.error.set(err?.error?.message ?? 'Failed to load documents.');
      }
    });
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.upload(file);
    input.value = '';
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.upload(file);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(): void {
    this.dragging.set(false);
  }

  upload(file: File): void {
    if (file.type !== 'application/pdf' && !file.name.toLowerCase().endsWith('.pdf')) {
      this.error.set('Only PDF files are supported.');
      return;
    }
    this.error.set(null);
    this.uploading.set(true);
    this.documentService.upload(file).subscribe({
      next: () => {
        this.uploading.set(false);
        this.refresh();
      },
      error: (err) => {
        this.uploading.set(false);
        this.error.set(err?.error?.message ?? 'Upload failed.');
      }
    });
  }

  open(doc: DocumentSummary): void {
    // The raw PDF is servable the instant it's uploaded - only Q&A needs indexing to finish, so
    // let the user start reading a still-PROCESSING document right away instead of blocking on it.
    if (doc.status === 'FAILED') return;
    this.router.navigate(['/documents', doc.id]);
  }

  remove(doc: DocumentSummary, event: Event): void {
    event.stopPropagation();
    this.documentService.delete(doc.id).subscribe(() => this.refresh());
  }

  logout(): void {
    this.authService.logout();
  }

  statusLabel(doc: DocumentSummary): string {
    switch (doc.status) {
      case 'PROCESSING':
        return 'Processing';
      case 'READY':
        return 'Ready';
      case 'FAILED':
        return 'Failed';
    }
  }

  processingEstimateSeconds(doc: DocumentSummary): number {
    return estimateProcessingSeconds(doc.pageCount);
  }

  statusBadgeClass(doc: DocumentSummary): string {
    switch (doc.status) {
      case 'PROCESSING':
        return 'bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300';
      case 'READY':
        return 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300';
      case 'FAILED':
        return 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300';
    }
  }
}

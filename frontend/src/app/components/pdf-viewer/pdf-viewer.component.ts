import {
  AfterViewInit,
  ChangeDetectorRef,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild
} from '@angular/core';
import { CommonModule } from '@angular/common';
import * as pdfjsLib from 'pdfjs-dist';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import { Rect } from '../../core/models';

pdfjsLib.GlobalWorkerOptions.workerSrc = '/pdf.worker.min.js';

const DOCUMENT_LOAD_TIMEOUT_MS = 20000;

interface PageRender {
  pageNumber: number;
  canvas: HTMLCanvasElement | null;
  overlay: HTMLDivElement;
  wrapper: HTMLDivElement;
  scale: number;
}

@Component({
  selector: 'app-pdf-viewer',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './pdf-viewer.component.html'
})
export class PdfViewerComponent implements AfterViewInit, OnChanges, OnDestroy {
  @Input({ required: true }) fileUrl!: string;
  @Input() targetPage: number | null = null;
  @Input() targetRects: Rect[] | null = null;
  /** Fired on any click within the document area - the parent uses this to dismiss the current
   *  evidence highlight once the user has looked at it and moved on. */
  @Output() documentClicked = new EventEmitter<void>();

  loading = true;
  error: string | null = null;
  /** How many pages have finished rendering so far, and the total once known - drives the progress text. */
  progress = { rendered: 0, total: 0 };

  @ViewChild('container', { static: true }) containerRef!: ElementRef<HTMLDivElement>;

  private pdfDoc: PDFDocumentProxy | null = null;
  private pages = new Map<number, PageRender>();
  private renderToken = 0;

  constructor(private cdr: ChangeDetectorRef) {}

  ngAfterViewInit(): void {
    this.loadDocument();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['fileUrl'] && !changes['fileUrl'].firstChange) {
      this.loadDocument();
    }
    if (changes['targetPage'] || changes['targetRects']) {
      this.applyHighlight();
    }
  }

  ngOnDestroy(): void {
    this.pdfDoc?.destroy();
  }

  retry(): void {
    this.loadDocument();
  }

  private async loadDocument(): Promise<void> {
    const myToken = ++this.renderToken;
    this.loading = true;
    this.error = null;
    this.progress = { rendered: 0, total: 0 };
    this.pages.clear();
    this.containerRef.nativeElement.innerHTML = '';
    this.cdr.detectChanges();

    try {
      // pdf.js resolves these promises via its Web Worker, whose message events are not
      // guaranteed to be patched by zone.js - so state changes here can land outside Angular's
      // zone and silently fail to trigger a re-render. detectChanges() calls below force the
      // update regardless of which zone we're actually running in.
      const pdf = await this.withTimeout(
        pdfjsLib.getDocument(this.fileUrl).promise,
        DOCUMENT_LOAD_TIMEOUT_MS,
        'Timed out opening the PDF (it may be unusually large or complex).'
      );
      if (myToken !== this.renderToken) return;

      this.pdfDoc = pdf;
      this.progress = { rendered: 0, total: pdf.numPages };
      this.cdr.detectChanges();

      for (let pageNumber = 1; pageNumber <= pdf.numPages; pageNumber++) {
        if (myToken !== this.renderToken) return;
        await this.renderPageSafely(pdf, pageNumber);
        this.progress = { ...this.progress, rendered: pageNumber };
        this.cdr.detectChanges();
      }

      this.loading = false;
      this.applyHighlight();
      this.cdr.detectChanges();
    } catch (err) {
      if (myToken !== this.renderToken) return;
      this.loading = false;
      this.error = err instanceof Error ? err.message : 'Failed to load PDF.';
      console.error(err);
      this.cdr.detectChanges();
    }
  }

  private withTimeout<T>(promise: Promise<T>, ms: number, timeoutMessage: string): Promise<T> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(timeoutMessage)), ms);
      promise.then(
        (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        (err) => {
          clearTimeout(timer);
          reject(err);
        }
      );
    });
  }

  /** One page failing to render (a bad embedded font, an unsupported feature, ...) must not take
   *  down the whole document - render a placeholder for that page and keep going. */
  private async renderPageSafely(pdf: PDFDocumentProxy, pageNumber: number): Promise<void> {
    try {
      await this.renderPage(pdf, pageNumber);
    } catch (err) {
      console.error(`Failed to render page ${pageNumber}`, err);
      this.renderFailedPagePlaceholder(pageNumber);
    }
  }

  private async renderPage(pdf: PDFDocumentProxy, pageNumber: number): Promise<void> {
    const page = await pdf.getPage(pageNumber);
    const viewport = page.getViewport({ scale: 1.3 });

    const pageContainer = document.createElement('div');
    pageContainer.className = 'mx-auto mb-4';
    pageContainer.style.width = `${viewport.width}px`;

    const wrapper = document.createElement('div');
    wrapper.className = 'relative shadow-sm';
    wrapper.style.width = `${viewport.width}px`;
    wrapper.style.height = `${viewport.height}px`;
    wrapper.setAttribute('data-page', String(pageNumber));

    const canvas = document.createElement('canvas');
    canvas.width = viewport.width;
    canvas.height = viewport.height;
    canvas.className = 'block bg-white';

    const overlay = document.createElement('div');
    overlay.className = 'absolute inset-0 pointer-events-none';

    wrapper.appendChild(canvas);
    wrapper.appendChild(overlay);

    const caption = document.createElement('div');
    caption.className = 'text-center text-xs text-muted py-1.5 select-none';
    caption.textContent = `Page ${pageNumber}`;

    pageContainer.appendChild(wrapper);
    pageContainer.appendChild(caption);
    this.containerRef.nativeElement.appendChild(pageContainer);

    const context = canvas.getContext('2d');
    if (context) {
      await this.withTimeout(
        page.render({ canvasContext: context, viewport }).promise,
        DOCUMENT_LOAD_TIMEOUT_MS,
        `Timed out rendering page ${pageNumber}.`
      );
    }

    // Selectable text layer: transparent text positioned exactly over the rendered glyphs, so
    // users can select/copy text even though what's visually painted is the canvas image.
    try {
      const textLayerDiv = document.createElement('div');
      textLayerDiv.className = 'textLayer';
      textLayerDiv.style.setProperty('--scale-factor', String(viewport.scale));
      wrapper.insertBefore(textLayerDiv, overlay);

      const textContent = await page.getTextContent();
      await pdfjsLib.renderTextLayer({ textContentSource: textContent, container: textLayerDiv, viewport }).promise;
    } catch (err) {
      console.warn(`Text layer unavailable for page ${pageNumber} - the page is still viewable, just not selectable.`, err);
    }

    this.pages.set(pageNumber, { pageNumber, canvas, overlay, wrapper, scale: viewport.scale });
  }

  private renderFailedPagePlaceholder(pageNumber: number): void {
    const wrapper = document.createElement('div');
    wrapper.className = 'relative mx-auto mb-4 shadow-sm bg-gray-50 dark:bg-gray-800 border border-dashed border-gray-300 dark:border-gray-600 flex items-center justify-center';
    wrapper.style.width = '612px';
    wrapper.style.height = '200px';
    wrapper.setAttribute('data-page', String(pageNumber));

    const message = document.createElement('p');
    message.className = 'text-sm text-gray-400 dark:text-gray-500';
    message.textContent = `Page ${pageNumber} could not be rendered.`;
    wrapper.appendChild(message);

    const overlay = document.createElement('div');
    overlay.className = 'absolute inset-0 pointer-events-none';
    wrapper.appendChild(overlay);

    this.containerRef.nativeElement.appendChild(wrapper);
    this.pages.set(pageNumber, { pageNumber, canvas: null, overlay, wrapper, scale: 1.3 });
  }

  private applyHighlight(): void {
    for (const page of this.pages.values()) {
      page.overlay.innerHTML = '';
    }

    if (!this.targetPage || !this.targetRects || this.targetRects.length === 0) return;

    const page = this.pages.get(this.targetPage);
    if (!page) return;

    for (const rect of this.targetRects) {
      const marker = document.createElement('div');
      marker.className = 'absolute bg-yellow-300/50 border border-yellow-500/70 rounded-sm animate-highlight-pulse';
      marker.style.left = `${rect.x * page.scale}px`;
      marker.style.top = `${rect.y * page.scale}px`;
      marker.style.width = `${rect.width * page.scale}px`;
      marker.style.height = `${rect.height * page.scale}px`;
      page.overlay.appendChild(marker);
    }

    page.wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

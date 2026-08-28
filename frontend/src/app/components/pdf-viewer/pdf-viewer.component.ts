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
/** Selections longer than this are truncated (with a trailing ellipsis) before being sent as a
 *  question/claim - long enough for a real paragraph or a short code snippet, short enough to
 *  stay a reasonably-sized embedding query and a readable bubble in the chat panel. */
const MAX_SELECTION_CHARS = 600;
/** A drag shorter than this (in either dimension, canvas pixels) is treated as an accidental
 *  click rather than a deliberate image-region selection. */
const MIN_REGION_DRAG_PX = 20;
/** Horizontal padding around a highlighted line box, in rendered pixels. */
const HIGHLIGHT_PADDING_X = 3;
/** Vertical padding when a highlight is a single line, or its spacing can't be measured. */
const HIGHLIGHT_FALLBACK_PADDING_Y = 4;
/** Upper bound on vertical padding, so an unusually large gap (a paragraph break inside the
 *  highlighted range) doesn't inflate every box into a slab. */
const HIGHLIGHT_MAX_PADDING_Y = 10;
/** Rendered toolbar height (~40px) + its connector arrow (~6px) + a small breathing-room gap -
 *  the minimum clearance needed above a selection for the toolbar to render above it without
 *  being clipped by (or overlapping into) whatever's above, e.g. a heading right above the
 *  selected line. Below that, it flips to render below the selection instead. */
const TOOLBAR_CLEARANCE_PX = 60;

type ToolbarPlacement = 'above' | 'below';

type SelectionToolbarState =
  | { kind: 'text'; x: number; y: number; placement: ToolbarPlacement; text: string }
  | { kind: 'image'; x: number; y: number; placement: ToolbarPlacement; imageDataUrl: string };

interface PageRender {
  pageNumber: number;
  canvas: HTMLCanvasElement | null;
  overlay: HTMLDivElement;
  wrapper: HTMLDivElement;
  scale: number;
  /** Null when the text layer failed to render (see renderPage's catch) or the page failed
   *  entirely - image-region selection still works in that case, since there's no real text to
   *  conflict with anyway. */
  textLayer: HTMLDivElement | null;
}

/** Tracks an in-progress drag over a page's canvas (i.e. NOT over the text layer) - the frontend
 *  half of "select an image region", for diagrams/charts/screenshots the text layer can't cover.
 *  Coordinates are canvas-pixel-relative, matching the coordinate space cropCanvasRegion() expects. */
interface RegionDragState {
  pageNumber: number;
  canvas: HTMLCanvasElement;
  wrapper: HTMLDivElement;
  startX: number;
  startY: number;
  boxEl: HTMLDivElement | null;
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
  /** Fired when the user picks "Explain" from the selection toolbar, with the selected text. */
  @Output() explainSelection = new EventEmitter<string>();
  /** Fired when the user picks "Summarize" (web-verified) from the selection toolbar. */
  @Output() summarizeSelection = new EventEmitter<string>();
  /** Same as explainSelection, for a dragged image region (a diagram/chart/screenshot) instead of
   *  selectable text - payload is a data: URL PNG crop of that region. */
  @Output() explainImageSelection = new EventEmitter<string>();
  /** Same as summarizeSelection, for a dragged image region. */
  @Output() summarizeImageSelection = new EventEmitter<string>();

  loading = true;
  error: string | null = null;
  /** How many pages have finished rendering so far, and the total once known - drives the progress text. */
  progress = { rendered: 0, total: 0 };
  /** Set while the user has an active text or image-region selection with a floating toolbar
   *  showing above it; null otherwise. Position is relative to the scrollable wrapper, including
   *  its current scroll offset, so it stays pinned to the selection rather than the viewport. */
  selectionToolbar: SelectionToolbarState | null = null;

  @ViewChild('container', { static: true }) containerRef!: ElementRef<HTMLDivElement>;

  private pdfDoc: PDFDocumentProxy | null = null;
  private pages = new Map<number, PageRender>();
  private renderToken = 0;
  /** Non-null only while the user is actively dragging out an image-region selection (mousedown
   *  landed outside the text layer, e.g. on a diagram). */
  private regionDrag: RegionDragState | null = null;

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

  /** Anticipates a new selection starting - hides any stale toolbar immediately rather than
   *  leaving it floating over the old (about to be replaced) selection while the user drags.
   *  Also decides here whether this drag is a text selection (let the browser handle it natively,
   *  as before) or an image-region selection (mousedown landed outside the text layer entirely,
   *  e.g. directly on a diagram/chart) - in which case we drive the drag ourselves. */
  onContainerMouseDown(event: MouseEvent): void {
    this.selectionToolbar = null;
    this.regionDrag = null;

    const target = event.target as HTMLElement;
    const wrapper = target.closest('[data-page]') as HTMLDivElement | null;
    const pageNumber = wrapper ? Number(wrapper.dataset['page']) : NaN;
    const page = this.pages.get(pageNumber);
    if (!wrapper || !page || !page.canvas) return;

    // pdf.js's text layer is one full-page container div with individual <span>s positioned only
    // where real text actually is - `elementFromPoint`/`closest('.textLayer')` both land on that
    // same full-page container even over a totally empty area (e.g. a diagram with no text nearby),
    // so they can't tell "over real text" from "over empty space". Checking each span's own
    // bounding box against the actual click point is the only reliable way to tell the two apart.
    if (this.pointIsOverRealText(page.textLayer, event.clientX, event.clientY)) return;

    const canvasRect = page.canvas.getBoundingClientRect();
    this.regionDrag = {
      pageNumber,
      canvas: page.canvas,
      wrapper,
      startX: event.clientX - canvasRect.left,
      startY: event.clientY - canvasRect.top,
      boxEl: null
    };
    // Stops the browser's own drag-to-select/image-drag behavior from fighting with our own
    // rectangle - without this, dragging over a canvas can trigger a native "ghost image" drag.
    event.preventDefault();
  }

  private pointIsOverRealText(textLayer: HTMLDivElement | null, clientX: number, clientY: number): boolean {
    if (!textLayer) return false;
    for (const span of Array.from(textLayer.querySelectorAll('span'))) {
      if (!span.textContent?.trim()) continue;
      const r = span.getBoundingClientRect();
      if (clientX >= r.left && clientX <= r.right && clientY >= r.top && clientY <= r.bottom) return true;
    }
    return false;
  }

  onContainerMouseMove(event: MouseEvent): void {
    if (!this.regionDrag) return;

    const canvasRect = this.regionDrag.canvas.getBoundingClientRect();
    const currentX = event.clientX - canvasRect.left;
    const currentY = event.clientY - canvasRect.top;
    const left = Math.min(this.regionDrag.startX, currentX);
    const top = Math.min(this.regionDrag.startY, currentY);
    const width = Math.abs(currentX - this.regionDrag.startX);
    const height = Math.abs(currentY - this.regionDrag.startY);

    const page = this.pages.get(this.regionDrag.pageNumber);
    if (!page) return;

    if (!this.regionDrag.boxEl) {
      const box = document.createElement('div');
      box.className = 'absolute border-2 border-accent bg-accent-wash pointer-events-none';
      page.overlay.appendChild(box);
      this.regionDrag.boxEl = box;
    }
    const box = this.regionDrag.boxEl;
    box.style.left = `${left}px`;
    box.style.top = `${top}px`;
    box.style.width = `${width}px`;
    box.style.height = `${height}px`;
  }

  onContainerMouseUp(event: MouseEvent): void {
    if (this.regionDrag) {
      this.finalizeRegionDrag(event);
      return;
    }

    const selection = window.getSelection();
    if (!selection || selection.isCollapsed || selection.rangeCount === 0) return;
    if (!this.containerRef.nativeElement.contains(selection.anchorNode)) return;

    const text = selection.toString().trim();
    if (!text) return;

    const range = selection.getRangeAt(0);
    // Anchor X on the FIRST LINE's own rect, not the whole range's union bounding box - a
    // multi-line selection's union box is as wide as its widest line, which can differ hugely
    // from the first line (e.g. a short line followed by a long one), pushing a box centered on
    // the union far to one side and off the visible (horizontally-scrollable) pane entirely.
    const lineRects = range.getClientRects();
    const firstLineRect = lineRects[0] ?? range.getBoundingClientRect();
    const lastLineRect = lineRects[lineRects.length - 1] ?? firstLineRect;

    const wrapper = this.containerRef.nativeElement.parentElement!;
    const placement = this.pickPlacement(firstLineRect.top, wrapper);
    const anchorViewportY = placement === 'above' ? firstLineRect.top : lastLineRect.bottom;

    this.selectionToolbar = {
      kind: 'text',
      x: this.clampToWrapperWidth(this.toWrapperX(firstLineRect.left + firstLineRect.width / 2, wrapper), wrapper),
      y: this.toWrapperY(anchorViewportY, wrapper),
      placement,
      text: text.length > MAX_SELECTION_CHARS ? text.slice(0, MAX_SELECTION_CHARS).trimEnd() + '…' : text
    };
  }

  private finalizeRegionDrag(event: MouseEvent): void {
    const drag = this.regionDrag!;
    this.regionDrag = null;
    drag.boxEl?.remove();

    const canvasRect = drag.canvas.getBoundingClientRect();
    const endX = event.clientX - canvasRect.left;
    const endY = event.clientY - canvasRect.top;
    const left = Math.min(drag.startX, endX);
    const top = Math.min(drag.startY, endY);
    const width = Math.abs(endX - drag.startX);
    const height = Math.abs(endY - drag.startY);
    if (width < MIN_REGION_DRAG_PX || height < MIN_REGION_DRAG_PX) return;

    const imageDataUrl = this.cropCanvasRegion(drag.canvas, left, top, width, height);
    if (!imageDataUrl) return;

    const wrapper = this.containerRef.nativeElement.parentElement!;
    const regionTopViewport = canvasRect.top + top;
    const regionBottomViewport = regionTopViewport + height;
    const placement = this.pickPlacement(regionTopViewport, wrapper);
    const anchorViewportY = placement === 'above' ? regionTopViewport : regionBottomViewport;

    this.selectionToolbar = {
      kind: 'image',
      x: this.clampToWrapperWidth(this.toWrapperX(canvasRect.left + left + width / 2, wrapper), wrapper),
      y: this.toWrapperY(anchorViewportY, wrapper),
      placement,
      imageDataUrl
    };
  }

  /** "above" the anchor unless there isn't enough visible clearance above it in the scrollable
   *  pane's current viewport - e.g. a selection whose first line sits right below a heading, or
   *  near the very top of the scrolled-into-view area - in which case the toolbar renders below
   *  the anchor instead, so it never overlaps whatever's above the selection. */
  private pickPlacement(anchorViewportTop: number, wrapper: HTMLElement): ToolbarPlacement {
    const wrapperRect = wrapper.getBoundingClientRect();
    return anchorViewportTop - wrapperRect.top >= TOOLBAR_CLEARANCE_PX ? 'above' : 'below';
  }

  private cropCanvasRegion(source: HTMLCanvasElement, x: number, y: number, width: number, height: number): string | null {
    const cropCanvas = document.createElement('canvas');
    cropCanvas.width = width;
    cropCanvas.height = height;
    const ctx = cropCanvas.getContext('2d');
    if (!ctx) return null;
    ctx.drawImage(source, x, y, width, height, 0, 0, width, height);
    return cropCanvas.toDataURL('image/png');
  }

  private toWrapperX(viewportX: number, wrapper: HTMLElement): number {
    return viewportX - wrapper.getBoundingClientRect().left + wrapper.scrollLeft;
  }

  private toWrapperY(viewportY: number, wrapper: HTMLElement): number {
    return viewportY - wrapper.getBoundingClientRect().top + wrapper.scrollTop;
  }

  /** Keeps the toolbar (roughly 170px wide) from rendering partly outside the visible pane, even
   *  when the anchor point sits near the left/right edge of the scrollable viewport. */
  private clampToWrapperWidth(x: number, wrapper: HTMLElement): number {
    const min = wrapper.scrollLeft + 90;
    const max = wrapper.scrollLeft + wrapper.clientWidth - 90;
    return Math.max(min, Math.min(x, max));
  }

  onExplainClick(): void {
    if (!this.selectionToolbar) return;
    if (this.selectionToolbar.kind === 'text') {
      this.explainSelection.emit(this.selectionToolbar.text);
    } else {
      this.explainImageSelection.emit(this.selectionToolbar.imageDataUrl);
    }
    this.dismissSelectionToolbar();
  }

  onSummarizeClick(): void {
    if (!this.selectionToolbar) return;
    if (this.selectionToolbar.kind === 'text') {
      this.summarizeSelection.emit(this.selectionToolbar.text);
    } else {
      this.summarizeImageSelection.emit(this.selectionToolbar.imageDataUrl);
    }
    this.dismissSelectionToolbar();
  }

  private dismissSelectionToolbar(): void {
    this.selectionToolbar = null;
    window.getSelection()?.removeAllRanges();
  }

  private async loadDocument(): Promise<void> {
    const myToken = ++this.renderToken;
    this.loading = true;
    this.error = null;
    this.progress = { rendered: 0, total: 0 };
    this.pages.clear();
    this.regionDrag = null;
    this.selectionToolbar = null;
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
    let textLayer: HTMLDivElement | null = null;
    try {
      const textLayerDiv = document.createElement('div');
      textLayerDiv.className = 'textLayer';
      textLayerDiv.style.setProperty('--scale-factor', String(viewport.scale));
      wrapper.insertBefore(textLayerDiv, overlay);

      const textContent = await page.getTextContent();
      await pdfjsLib.renderTextLayer({ textContentSource: textContent, container: textLayerDiv, viewport }).promise;
      textLayer = textLayerDiv;
    } catch (err) {
      console.warn(`Text layer unavailable for page ${pageNumber} - the page is still viewable, just not selectable.`, err);
    }

    this.pages.set(pageNumber, { pageNumber, canvas, overlay, wrapper, scale: viewport.scale, textLayer });
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
    this.pages.set(pageNumber, { pageNumber, canvas: null, overlay, wrapper, scale: 1.3, textLayer: null });
  }

  /**
   * Half the actual vertical gap between consecutive highlighted lines, so padding each box by this
   * much makes them meet exactly and the highlight reads as one continuous region.
   *
   * Derived from the rects rather than fixed, because the gap depends on the document's line
   * spacing and the current render scale - a constant that closes the gap in one document leaves
   * stripes in another, or bloats a tightly-set one.
   */
  private verticalHighlightPadding(rects: { top: number; height: number }[]): number {
    const gaps: number[] = [];
    for (let i = 1; i < rects.length; i++) {
      const gap = rects[i].top - (rects[i - 1].top + rects[i - 1].height);
      if (gap > 0) gaps.push(gap);
    }
    if (gaps.length === 0) return HIGHLIGHT_FALLBACK_PADDING_Y;

    gaps.sort((a, b) => a - b);
    const medianGap = gaps[Math.floor(gaps.length / 2)];
    return Math.min(HIGHLIGHT_MAX_PADDING_Y, Math.max(HIGHLIGHT_FALLBACK_PADDING_Y, medianGap / 2));
  }

  private applyHighlight(): void {
    for (const page of this.pages.values()) {
      page.overlay.innerHTML = '';
    }

    if (!this.targetPage || !this.targetRects || this.targetRects.length === 0) return;

    const page = this.pages.get(this.targetPage);
    if (!page) return;

    // Line boxes hug the glyphs, so drawing them as-is leaves visible gaps between lines and reads
    // as a stack of separate stripes rather than one highlighted passage. Padding each box - and
    // squaring off the left edge across the group - makes consecutive lines meet, so a multi-line
    // highlight covers the whole region the text occupies.
    const scaled = this.targetRects.map((rect) => ({
      left: rect.x * page.scale,
      top: rect.y * page.scale,
      width: rect.width * page.scale,
      height: rect.height * page.scale
    }));
    const groupLeft = Math.min(...scaled.map((r) => r.left));
    const paddingY = this.verticalHighlightPadding(scaled);

    for (const rect of scaled) {
      const marker = document.createElement('div');
      marker.className = 'absolute bg-sky-400/20 border border-sky-400/35 rounded animate-highlight-pulse';
      marker.style.left = `${groupLeft - HIGHLIGHT_PADDING_X}px`;
      marker.style.top = `${rect.top - paddingY}px`;
      marker.style.width = `${rect.left - groupLeft + rect.width + HIGHLIGHT_PADDING_X * 2}px`;
      marker.style.height = `${rect.height + paddingY * 2}px`;
      page.overlay.appendChild(marker);
    }

    page.wrapper.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

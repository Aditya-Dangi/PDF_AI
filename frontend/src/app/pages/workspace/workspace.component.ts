import { Component, ElementRef, OnDestroy, OnInit, signal, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { DocumentService } from '../../core/document.service';
import { ConversationService } from '../../core/conversation.service';
import { ClaimService } from '../../core/claim.service';
import { AnswerMode, AnswerResponse, ChatMessage, Claim, DocumentBlock, DocumentSummary, Evidence, FactCheckResponse, Rect, SummaryResponse } from '../../core/models';
import { PdfViewerComponent } from '../../components/pdf-viewer/pdf-viewer.component';
import { AnswerCardComponent } from '../../components/answer-card/answer-card.component';
import { SummaryCardComponent } from '../../components/summary-card/summary-card.component';
import { DocumentTextPaneComponent } from '../../components/document-text-pane/document-text-pane.component';
import { AuditPanelComponent } from '../../components/audit-panel/audit-panel.component';
import { LoadingTimerComponent } from '../../components/loading-timer/loading-timer.component';
import { ThemeToggleComponent } from '../../components/theme-toggle/theme-toggle.component';
import { TIME_ESTIMATES, estimateProcessingSeconds } from '../../core/time-estimates';

/** A grounded Q&A exchange (Ask, or "Explain" from the selection toolbar). */
interface AnswerTimelineEntry {
  kind: 'answer';
  messageId: string;
  question: string | null;
  answer: AnswerResponse;
  factCheck: FactCheckResponse | null;
  claims: Claim[] | null;
}

/** A plain summary (from "Summarize" in the selection toolbar, or an edited resend of one). */
interface SummaryTimelineEntry {
  kind: 'summary';
  messageId: string;
  summary: SummaryResponse;
}

/** A standalone web fact-check of a typed claim ("Fact-check a claim" panel) - distinct from the
 *  "Fact-check this claim" button on an existing answer, which stays attached to that AnswerTimelineEntry. */
interface FactCheckTimelineEntry {
  kind: 'factcheck';
  messageId: string;
  factCheck: FactCheckResponse;
}

type TimelineEntry = AnswerTimelineEntry | SummaryTimelineEntry | FactCheckTimelineEntry;

@Component({
  selector: 'app-workspace',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    PdfViewerComponent,
    AnswerCardComponent,
    SummaryCardComponent,
    DocumentTextPaneComponent,
    AuditPanelComponent,
    LoadingTimerComponent,
    ThemeToggleComponent
  ],
  templateUrl: './workspace.component.html'
})
export class WorkspaceComponent implements OnInit, OnDestroy {
  readonly askEstimateSeconds = TIME_ESTIMATES.ask;
  readonly factCheckEstimateSeconds = TIME_ESTIMATES.factCheck;

  document = signal<DocumentSummary | null>(null);
  fileUrl = signal<string | null>(null);
  /** Every Ask/Explain/Summarize/Fact-check result, in the order they happened - a single
   *  chronological feed (newest at the bottom, like a normal chat) rather than separate regions
   *  per action type. */
  timeline = signal<TimelineEntry[]>([]);
  loadError = signal<string | null>(null);

  question = '';
  asking = signal(false);
  /** Fast = one pass (today's behavior). Quality = iterative gap-filling research; slower, and
   *  bounded server-side by DeepResearchService's stop conditions. */
  answerMode = signal<AnswerMode>('FAST');
  /** Right pane: the AI conversation, or the document's extracted text. */
  rightPane = signal<'ai' | 'text'>('ai');
  summarizing = signal(false);

  claimText = '';
  showClaimInput = signal(false);
  claimChecking = signal(false);

  factCheckLoadingFor = signal<string | null>(null);
  decomposingFor = signal<string | null>(null);
  challengingClaimId = signal<string | null>(null);
  showAuditPanel = signal(false);

  /** Messages checked via their selection checkbox, for bulk delete. */
  selectedMessageIds = signal<Set<string>>(new Set());
  /** The one message currently being edited (only one at a time), if any. */
  editingMessageId = signal<string | null>(null);
  editDraft = '';

  targetPage = signal<number | null>(null);
  targetRects = signal<Rect[] | null>(null);

  private documentId!: string;
  private pollHandle: ReturnType<typeof setInterval> | null = null;

  @ViewChild('messagesEnd') private messagesEnd?: ElementRef<HTMLDivElement>;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private documentService: DocumentService,
    private conversationService: ConversationService,
    private claimService: ClaimService
  ) {}

  ngOnInit(): void {
    this.documentId = this.route.snapshot.paramMap.get('id')!;
    this.load();
  }

  ngOnDestroy(): void {
    if (this.pollHandle) clearInterval(this.pollHandle);
  }

  private async load(): Promise<void> {
    try {
      const doc = await firstValueFrom(this.documentService.get(this.documentId));
      this.document.set(doc);

      // The raw file is servable the instant it's uploaded - show it immediately regardless of
      // indexing status, rather than making the user wait through the whole pipeline just to read
      // the document. Only Q&A/Audit actually need indexing (status READY) to work.
      const url = await this.documentService.fetchFileBlobUrl(this.documentId);
      this.fileUrl.set(url);

      if (doc.status === 'PROCESSING') {
        this.pollHandle = setInterval(() => this.pollStatus(), 3000);
      } else if (doc.status === 'READY') {
        const history = await firstValueFrom(this.conversationService.history(this.documentId));
        this.timeline.set(this.toTimeline(history));
      }
    } catch (err) {
      this.loadError.set('Failed to load document.');
      console.error(err);
    }
  }

  private async pollStatus(): Promise<void> {
    const doc = await firstValueFrom(this.documentService.get(this.documentId));
    this.document.set(doc);
    if (doc.status === 'PROCESSING') return;

    if (this.pollHandle) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
    if (doc.status === 'READY') {
      const history = await firstValueFrom(this.conversationService.history(this.documentId));
      this.timeline.set(this.toTimeline(history));
    }
  }

  private toTimeline(messages: ChatMessage[]): TimelineEntry[] {
    const result: TimelineEntry[] = [];
    for (let i = 0; i < messages.length; i++) {
      const m = messages[i];
      if (m.role !== 'ASSISTANT') continue;
      const userMsg = i > 0 && messages[i - 1].role === 'USER' ? messages[i - 1] : null;

      if (m.answer) {
        result.push({ kind: 'answer', messageId: m.id, question: userMsg?.content ?? null, answer: m.answer, factCheck: m.factCheck, claims: null });
      } else if (m.summary) {
        result.push({ kind: 'summary', messageId: m.id, summary: m.summary });
      } else if (m.factCheck) {
        result.push({ kind: 'factcheck', messageId: m.id, factCheck: m.factCheck });
      }
    }
    return result;
  }

  ask(): void {
    const q = this.question.trim();
    if (!q || this.asking()) return;

    this.asking.set(true);
    this.conversationService.ask(this.documentId, q, this.answerMode()).subscribe({
      next: (answer) => {
        this.asking.set(false);
        this.question = '';
        this.pushEntry({ kind: 'answer', messageId: answer.messageId, question: q, answer, factCheck: null, claims: null });
      },
      error: (err) => {
        this.asking.set(false);
        this.loadError.set(err?.error?.message ?? 'Failed to get an answer.');
      }
    });
  }

  /** "Explain" from the PDF selection toolbar - reuses the normal ask() flow verbatim, feeding the
   *  selected passage as the question. Since the selection came directly from the document, its
   *  embedding is a near-exact match for the source chunk it was taken from, so grounded retrieval
   *  finds the right context automatically with no new backend work needed. */
  explainSelection(text: string): void {
    if (!this.requireReady('Questions')) return;
    this.question = `Explain this: "${text}"`;
    this.ask();
  }

  /** "Summarize" from the PDF selection toolbar - a plain summary of the selected passage itself
   *  (not a fact-check: no claim extraction, no web search, no verdict - see SummarizationService
   *  on the backend). */
  summarizeSelection(text: string): void {
    if (!this.requireReady('Summarizing')) return;
    this.pushSummaryFromText(text);
  }

  /** "Explain" from the PDF selection toolbar, for a dragged image region (a diagram/chart the
   *  text layer can't cover) instead of selectable text - same idea as explainSelection() but the
   *  backend needs the actual image bytes (OCR/vision resolution happens server-side), so this
   *  can't just funnel through ask(). */
  explainImageSelection(imageDataUrl: string): void {
    if (!this.requireReady('Questions')) return;
    this.asking.set(true);
    this.conversationService.askImage(this.documentId, imageDataUrl).subscribe({
      next: (answer) => {
        this.asking.set(false);
        // answer.question is the backend's OCR/vision-resolved text for the selected region, not
        // a placeholder - showing it verbatim is what actually tells the user what got selected.
        this.pushEntry({ kind: 'answer', messageId: answer.messageId, question: answer.question, answer, factCheck: null, claims: null });
      },
      error: (err) => {
        this.asking.set(false);
        this.loadError.set(err?.error?.message ?? 'Failed to analyze the selected image region.');
      }
    });
  }

  /** "Summarize" from the PDF selection toolbar, for a dragged image region - same idea as
   *  summarizeSelection() but the backend resolves the image (OCR, falling back to a vision-model
   *  description) before summarizing it. */
  summarizeImageSelection(imageDataUrl: string): void {
    if (!this.requireReady('Summarizing')) return;
    this.summarizing.set(true);
    this.conversationService.summarizeImage(this.documentId, imageDataUrl).subscribe({
      next: (summary) => {
        this.summarizing.set(false);
        this.pushEntry({ kind: 'summary', messageId: summary.messageId, summary });
      },
      error: (err) => {
        this.summarizing.set(false);
        this.loadError.set(err?.error?.message ?? 'Failed to summarize the selected image region.');
      }
    });
  }

  requestFactCheck(entry: AnswerTimelineEntry): void {
    this.factCheckLoadingFor.set(entry.messageId);
    this.conversationService.factCheckMessage(this.documentId, entry.messageId).subscribe({
      next: (result) => {
        this.factCheckLoadingFor.set(null);
        this.timeline.update((list) =>
          list.map((e) => (e.kind === 'answer' && e.messageId === entry.messageId ? { ...e, factCheck: result } : e))
        );
      },
      error: (err) => {
        this.factCheckLoadingFor.set(null);
        this.loadError.set(err?.error?.message ?? 'Fact-check failed.');
      }
    });
  }

  requestDecompose(entry: AnswerTimelineEntry): void {
    this.decomposingFor.set(entry.messageId);
    this.claimService.decomposeMessage(this.documentId, entry.messageId).subscribe({
      next: (claims) => {
        this.decomposingFor.set(null);
        this.timeline.update((list) =>
          list.map((e) => (e.kind === 'answer' && e.messageId === entry.messageId ? { ...e, claims } : e))
        );
      },
      error: (err) => {
        this.decomposingFor.set(null);
        this.loadError.set(err?.error?.message ?? 'Failed to break this claim down.');
      }
    });
  }

  requestChallenge(entry: AnswerTimelineEntry, claim: Claim): void {
    this.challengingClaimId.set(claim.id);
    this.claimService.challenge(this.documentId, claim.id).subscribe({
      next: (updated) => {
        this.challengingClaimId.set(null);
        this.timeline.update((list) =>
          list.map((e) =>
            e.kind === 'answer' && e.messageId === entry.messageId && e.claims
              ? { ...e, claims: [...e.claims, updated] }
              : e
          )
        );
      },
      error: (err) => {
        this.challengingClaimId.set(null);
        this.loadError.set(err?.error?.message ?? 'Challenge failed.');
      }
    });
  }

  /** The standalone "Fact-check a claim" panel - a typed, arbitrary claim (not necessarily even
   *  about this document). Unlike Summarize, this one genuinely IS the full claim/verdict/sources
   *  pipeline - that's what this specific feature is for. */
  checkClaim(): void {
    const text = this.claimText.trim();
    if (!text || this.claimChecking()) return;
    this.claimText = '';
    this.pushFactCheckFromClaim(text);
  }

  private pushSummaryFromText(text: string): void {
    this.summarizing.set(true);
    this.conversationService.summarizeText(this.documentId, text).subscribe({
      next: (summary) => {
        this.summarizing.set(false);
        this.pushEntry({ kind: 'summary', messageId: summary.messageId, summary });
      },
      error: (err) => {
        this.summarizing.set(false);
        this.loadError.set(err?.error?.message ?? 'Failed to summarize the selection.');
      }
    });
  }

  private pushFactCheckFromClaim(text: string): void {
    this.claimChecking.set(true);
    this.conversationService.factCheckClaim(this.documentId, text).subscribe({
      next: (result) => {
        this.claimChecking.set(false);
        this.pushEntry({ kind: 'factcheck', messageId: result.messageId, factCheck: result });
      },
      error: (err) => {
        this.claimChecking.set(false);
        this.loadError.set(err?.error?.message ?? 'Fact-check failed.');
      }
    });
  }

  /** Appends to the end of the timeline and scrolls it into view - every creation flow (Ask,
   *  Explain, Summarize, Fact-check a claim, text or image) goes through this one path, so they
   *  all behave like one consistent chat feed instead of some appearing in a separate panel. */
  private pushEntry(entry: TimelineEntry): void {
    this.timeline.update((list) => [...list, entry]);
    this.scrollToLatestEntry();
  }

  /** setTimeout lets Angular finish rendering the new entry (and its fade-in animation start)
   *  before we measure scroll height - scrolling in the same tick would use the pre-render height. */
  private scrollToLatestEntry(): void {
    setTimeout(() => this.messagesEnd?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'end' }));
  }

  private requireReady(actionLabel: string): boolean {
    if (this.document()?.status !== 'READY') {
      this.loadError.set(`${actionLabel} are available once this document finishes indexing.`);
      return false;
    }
    return true;
  }

  // --- Per-message actions: copy / edit / delete / bulk delete / clear all ---

  toggleSelected(messageId: string): void {
    this.selectedMessageIds.update((set) => {
      const next = new Set(set);
      if (next.has(messageId)) next.delete(messageId);
      else next.add(messageId);
      return next;
    });
  }

  copyEntry(entry: TimelineEntry): void {
    const text = this.entryCopyText(entry);
    navigator.clipboard?.writeText(text).catch(() => this.loadError.set('Could not copy to clipboard.'));
  }

  private entryCopyText(entry: TimelineEntry): string {
    switch (entry.kind) {
      case 'answer':
        return [entry.question ? `Q: ${entry.question}` : null, entry.answer.documentClaim, entry.answer.explanation]
          .filter((part): part is string => !!part)
          .join('\n\n');
      case 'summary':
        return entry.summary.summaryText;
      case 'factcheck':
        return `Claim: ${entry.factCheck.claimText}\nVerdict: ${entry.factCheck.verdict}\n\n${entry.factCheck.summary}`;
    }
  }

  /** Only entries with editable "your input" text get an edit button - the AI's own answers
   *  aren't editable, only what you asked/selected/typed that produced them. */
  canEdit(entry: TimelineEntry): boolean {
    return entry.kind !== 'answer' || entry.question != null;
  }

  private editableSourceText(entry: TimelineEntry): string {
    switch (entry.kind) {
      case 'answer':
        return entry.question ?? '';
      case 'summary':
        return entry.summary.sourceText;
      case 'factcheck':
        return entry.factCheck.claimText;
    }
  }

  startEdit(entry: TimelineEntry): void {
    this.editingMessageId.set(entry.messageId);
    this.editDraft = this.editableSourceText(entry);
  }

  cancelEdit(): void {
    this.editingMessageId.set(null);
    this.editDraft = '';
  }

  /** Edits your question/selection/claim and resends it - replaces this entry and everything
   *  after it (in the same document/document-agnostic conversation) with a fresh answer, the same
   *  way editing a message works in most chat tools. */
  async saveEdit(entry: TimelineEntry): Promise<void> {
    const newText = this.editDraft.trim();
    if (!newText) return;
    this.cancelEdit();

    const index = this.timeline().findIndex((e) => e.messageId === entry.messageId);
    if (index === -1) return;
    const removed = this.timeline().slice(index);
    this.timeline.set(this.timeline().slice(0, index));
    this.selectedMessageIds.update((set) => {
      const next = new Set(set);
      for (const e of removed) next.delete(e.messageId);
      return next;
    });

    try {
      await Promise.all(removed.map((e) => firstValueFrom(this.conversationService.deleteMessage(this.documentId, e.messageId))));
    } catch {
      // Best-effort - a stray orphaned row server-side isn't worth blocking the resend over.
    }

    switch (entry.kind) {
      case 'answer':
        this.question = newText;
        this.ask();
        break;
      case 'summary':
        this.pushSummaryFromText(newText);
        break;
      case 'factcheck':
        this.pushFactCheckFromClaim(newText);
        break;
    }
  }

  deleteEntry(messageId: string): void {
    this.conversationService.deleteMessage(this.documentId, messageId).subscribe({
      next: () => {
        this.timeline.update((list) => list.filter((e) => e.messageId !== messageId));
        this.selectedMessageIds.update((set) => {
          const next = new Set(set);
          next.delete(messageId);
          return next;
        });
      },
      error: (err) => this.loadError.set(err?.error?.message ?? 'Failed to delete this message.')
    });
  }

  deleteSelected(): void {
    const ids = Array.from(this.selectedMessageIds());
    if (ids.length === 0) return;
    if (!confirm(`Delete ${ids.length} selected message${ids.length > 1 ? 's' : ''}? This cannot be undone.`)) return;
    for (const id of ids) this.deleteEntry(id);
  }

  clearAll(): void {
    if (this.timeline().length === 0) return;
    if (!confirm('Delete all messages in this conversation? This cannot be undone.')) return;
    this.conversationService.clearMessages(this.documentId).subscribe({
      next: () => {
        this.timeline.set([]);
        this.selectedMessageIds.set(new Set());
      },
      error: (err) => this.loadError.set(err?.error?.message ?? 'Failed to clear messages.')
    });
  }

  /** A block clicked in the Text pane highlights on the PDF through the same overlay path as
   *  answer evidence - one highlight mechanism, not two. */
  onBlockSelected(block: DocumentBlock): void {
    this.targetPage.set(block.page);
    this.targetRects.set(block.rects);
  }

  onEvidenceSelected(evidence: Evidence): void {
    this.targetPage.set(evidence.page);
    this.targetRects.set(evidence.rects);
  }

  /** Clicking the document itself dismisses whatever highlight is currently shown - clicking the
   *  same (or another) evidence chip afterward brings it right back, since that always sets these
   *  signals fresh regardless of their previous value. */
  clearHighlight(): void {
    this.targetPage.set(null);
    this.targetRects.set(null);
  }

  backToDocuments(): void {
    this.router.navigate(['/documents']);
  }

  processingEstimateSeconds(): number {
    return estimateProcessingSeconds(this.document()?.pageCount ?? 0);
  }
}

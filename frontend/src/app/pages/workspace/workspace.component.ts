import { Component, ElementRef, OnDestroy, OnInit, signal, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { DocumentService } from '../../core/document.service';
import { ConversationService } from '../../core/conversation.service';
import { ClaimService } from '../../core/claim.service';
import { AnswerResponse, ChatMessage, Claim, DocumentSummary, Evidence, FactCheckResponse, Rect } from '../../core/models';
import { PdfViewerComponent } from '../../components/pdf-viewer/pdf-viewer.component';
import { AnswerCardComponent } from '../../components/answer-card/answer-card.component';
import { AuditPanelComponent } from '../../components/audit-panel/audit-panel.component';
import { LoadingTimerComponent } from '../../components/loading-timer/loading-timer.component';
import { ThemeToggleComponent } from '../../components/theme-toggle/theme-toggle.component';
import { TIME_ESTIMATES, estimateProcessingSeconds } from '../../core/time-estimates';

interface AnsweredExchange {
  messageId: string;
  question: string | null;
  answer: AnswerResponse;
  factCheck: FactCheckResponse | null;
  claims: Claim[] | null;
}

@Component({
  selector: 'app-workspace',
  standalone: true,
  imports: [CommonModule, FormsModule, PdfViewerComponent, AnswerCardComponent, AuditPanelComponent, LoadingTimerComponent, ThemeToggleComponent],
  templateUrl: './workspace.component.html'
})
export class WorkspaceComponent implements OnInit, OnDestroy {
  readonly askEstimateSeconds = TIME_ESTIMATES.ask;
  readonly factCheckEstimateSeconds = TIME_ESTIMATES.factCheck;

  document = signal<DocumentSummary | null>(null);
  fileUrl = signal<string | null>(null);
  exchanges = signal<AnsweredExchange[]>([]);
  loadError = signal<string | null>(null);

  question = '';
  asking = signal(false);

  claimText = '';
  showClaimInput = signal(false);
  claimChecking = signal(false);
  claimResult = signal<FactCheckResponse | null>(null);

  factCheckLoadingFor = signal<string | null>(null);
  decomposingFor = signal<string | null>(null);
  challengingClaimId = signal<string | null>(null);
  showAuditPanel = signal(false);

  targetPage = signal<number | null>(null);
  targetRects = signal<Rect[] | null>(null);

  private documentId!: string;
  private pollHandle: ReturnType<typeof setInterval> | null = null;

  @ViewChild('messagesEnd') private messagesEnd?: ElementRef<HTMLDivElement>;
  @ViewChild('claimPanel') private claimPanel?: ElementRef<HTMLDivElement>;

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
        this.exchanges.set(this.toExchanges(history));
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
      this.exchanges.set(this.toExchanges(history));
    }
  }

  private toExchanges(messages: ChatMessage[]): AnsweredExchange[] {
    const result: AnsweredExchange[] = [];
    for (let i = 0; i < messages.length; i++) {
      const m = messages[i];
      if (m.role === 'ASSISTANT' && m.answer) {
        const userMsg = i > 0 && messages[i - 1].role === 'USER' ? messages[i - 1] : null;
        result.push({
          messageId: m.id,
          question: userMsg?.content ?? null,
          answer: m.answer,
          factCheck: m.factCheck,
          claims: null
        });
      }
    }
    return result;
  }

  ask(): void {
    const q = this.question.trim();
    if (!q || this.asking()) return;

    this.asking.set(true);
    this.conversationService.ask(this.documentId, q).subscribe({
      next: (answer) => {
        this.asking.set(false);
        this.question = '';
        this.exchanges.update((list) => [
          ...list,
          { messageId: answer.messageId, question: q, answer, factCheck: null, claims: null }
        ]);
        this.scrollToLatestAnswer();
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
    if (this.document()?.status !== 'READY') {
      this.loadError.set('Questions are available once this document finishes indexing.');
      return;
    }
    this.question = `Explain this: "${text}"`;
    this.ask();
  }

  /** "Summarize" from the PDF selection toolbar - reuses the existing web fact-check flow verbatim
   *  (same claim-checking pipeline as the "Fact-check a claim" panel), so the selected passage gets
   *  a web-verified summary with precise source attribution, not just a plain LLM paraphrase. */
  summarizeSelection(text: string): void {
    if (this.document()?.status !== 'READY') {
      this.loadError.set('Fact-checking is available once this document finishes indexing.');
      return;
    }
    this.claimText = text;
    this.showClaimInput.set(true);
    this.checkClaim();
    // The claim panel renders at the top of the (possibly long, already-scrolled) conversation
    // list - without this, triggering it from a selection deep in a long chat could open it
    // entirely out of view.
    setTimeout(() => this.claimPanel?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  /** "Explain" from the PDF selection toolbar, for a dragged image region (a diagram/chart the
   *  text layer can't cover) instead of selectable text - same idea as explainSelection() but the
   *  backend needs the actual image bytes (OCR/vision resolution happens server-side), so this
   *  can't just funnel through ask(). */
  explainImageSelection(imageDataUrl: string): void {
    if (this.document()?.status !== 'READY') {
      this.loadError.set('Questions are available once this document finishes indexing.');
      return;
    }
    this.asking.set(true);
    this.conversationService.askImage(this.documentId, imageDataUrl).subscribe({
      next: (answer) => {
        this.asking.set(false);
        this.exchanges.update((list) => [
          ...list,
          { messageId: answer.messageId, question: '🖼️ Selected image region', answer, factCheck: null, claims: null }
        ]);
        this.scrollToLatestAnswer();
      },
      error: (err) => {
        this.asking.set(false);
        this.loadError.set(err?.error?.message ?? 'Failed to analyze the selected image region.');
      }
    });
  }

  /** "Summarize" from the PDF selection toolbar, for a dragged image region - same idea as
   *  summarizeSelection() but for image bytes instead of text. */
  summarizeImageSelection(imageDataUrl: string): void {
    if (this.document()?.status !== 'READY') {
      this.loadError.set('Fact-checking is available once this document finishes indexing.');
      return;
    }
    this.showClaimInput.set(true);
    this.claimText = '🖼️ Selected image region';
    this.claimChecking.set(true);
    this.claimResult.set(null);
    this.conversationService.factCheckImage(this.documentId, imageDataUrl).subscribe({
      next: (result) => {
        this.claimChecking.set(false);
        this.claimResult.set(result);
      },
      error: (err) => {
        this.claimChecking.set(false);
        this.loadError.set(err?.error?.message ?? 'Failed to fact-check the selected image region.');
      }
    });
    setTimeout(() => this.claimPanel?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  requestFactCheck(exchange: AnsweredExchange): void {
    this.factCheckLoadingFor.set(exchange.messageId);
    this.conversationService.factCheckMessage(this.documentId, exchange.messageId).subscribe({
      next: (result) => {
        this.factCheckLoadingFor.set(null);
        this.exchanges.update((list) =>
          list.map((e) => (e.messageId === exchange.messageId ? { ...e, factCheck: result } : e))
        );
      },
      error: (err) => {
        this.factCheckLoadingFor.set(null);
        this.loadError.set(err?.error?.message ?? 'Fact-check failed.');
      }
    });
  }

  requestDecompose(exchange: AnsweredExchange): void {
    this.decomposingFor.set(exchange.messageId);
    this.claimService.decomposeMessage(this.documentId, exchange.messageId).subscribe({
      next: (claims) => {
        this.decomposingFor.set(null);
        this.exchanges.update((list) =>
          list.map((e) => (e.messageId === exchange.messageId ? { ...e, claims } : e))
        );
      },
      error: (err) => {
        this.decomposingFor.set(null);
        this.loadError.set(err?.error?.message ?? 'Failed to break this claim down.');
      }
    });
  }

  requestChallenge(exchange: AnsweredExchange, claim: Claim): void {
    this.challengingClaimId.set(claim.id);
    this.claimService.challenge(this.documentId, claim.id).subscribe({
      next: (updated) => {
        this.challengingClaimId.set(null);
        this.exchanges.update((list) =>
          list.map((e) =>
            e.messageId === exchange.messageId && e.claims
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

  checkClaim(): void {
    const text = this.claimText.trim();
    if (!text || this.claimChecking()) return;

    this.claimChecking.set(true);
    this.claimResult.set(null);
    this.conversationService.factCheckClaim(this.documentId, text).subscribe({
      next: (result) => {
        this.claimChecking.set(false);
        this.claimResult.set(result);
      },
      error: (err) => {
        this.claimChecking.set(false);
        this.loadError.set(err?.error?.message ?? 'Fact-check failed.');
      }
    });
  }

  /** setTimeout lets Angular finish rendering the new answer-card (and its fade-in animation start)
   *  before we measure scroll height - scrolling in the same tick would use the pre-render height. */
  private scrollToLatestAnswer(): void {
    setTimeout(() => this.messagesEnd?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'end' }));
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

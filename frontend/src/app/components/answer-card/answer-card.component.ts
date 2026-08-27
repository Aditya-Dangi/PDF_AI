import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AnswerResponse, Claim, Evidence, FactCheckResponse } from '../../core/models';
import { ConfidenceBadgeComponent } from '../confidence-badge/confidence-badge.component';
import { ClaimCardComponent } from '../claim-card/claim-card.component';
import { LoadingTimerComponent } from '../loading-timer/loading-timer.component';
import { TIME_ESTIMATES } from '../../core/time-estimates';

const VERDICT_LABELS: Record<string, string> = {
  SUPPORTED: 'Supported',
  PARTIALLY_SUPPORTED: 'Partially Supported',
  MISLEADING: 'Misleading',
  UNSUPPORTED: 'Unsupported',
  CONTRADICTED: 'Contradicted',
  INSUFFICIENT_EVIDENCE: 'Insufficient Evidence'
};

@Component({
  selector: 'app-answer-card',
  standalone: true,
  imports: [CommonModule, ConfidenceBadgeComponent, ClaimCardComponent, LoadingTimerComponent],
  templateUrl: './answer-card.component.html'
})
export class AnswerCardComponent {
  readonly factCheckEstimateSeconds = TIME_ESTIMATES.factCheck;
  readonly decomposeEstimateSeconds = TIME_ESTIMATES.decompose;

  @Input({ required: true }) answer!: AnswerResponse;
  @Input() question: string | null = null;
  @Input() factCheck: FactCheckResponse | null = null;
  @Input() factCheckLoading = false;
  @Input() claims: Claim[] | null = null;
  @Input() decomposing = false;
  @Input() challengingClaimId: string | null = null;

  @Output() evidenceSelected = new EventEmitter<Evidence>();
  @Output() factCheckRequested = new EventEmitter<void>();
  @Output() decomposeRequested = new EventEmitter<void>();
  @Output() challengeRequested = new EventEmitter<Claim>();

  verdictLabel(v: string): string {
    return VERDICT_LABELS[v] ?? v;
  }

  verdictClass(v: string): string {
    switch (v) {
      case 'SUPPORTED':
        return 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300';
      case 'PARTIALLY_SUPPORTED':
        return 'bg-lime-100 text-lime-800 dark:bg-lime-900/40 dark:text-lime-300';
      case 'MISLEADING':
        return 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300';
      case 'UNSUPPORTED':
        return 'bg-orange-100 text-orange-800 dark:bg-orange-900/40 dark:text-orange-300';
      case 'CONTRADICTED':
        return 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300';
      default:
        return 'bg-surface-alt text-muted';
    }
  }

  stanceClass(stance: string): string {
    switch (stance) {
      case 'SUPPORTS':
        return 'text-green-700 dark:text-green-400';
      case 'CONTRADICTS':
        return 'text-red-700 dark:text-red-400';
      case 'MIXED':
        return 'text-amber-700 dark:text-amber-400';
      default:
        return 'text-muted';
    }
  }
}

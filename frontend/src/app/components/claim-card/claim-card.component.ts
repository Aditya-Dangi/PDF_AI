import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Claim, Evidence } from '../../core/models';
import { ConfidenceBadgeComponent } from '../confidence-badge/confidence-badge.component';
import { LoadingTimerComponent } from '../loading-timer/loading-timer.component';
import { TIME_ESTIMATES } from '../../core/time-estimates';
import { DomainPipe } from '../../core/domain.pipe';
import { MarkdownPipe } from '../../core/markdown.pipe';

const VERDICT_LABELS: Record<string, string> = {
  SUPPORTED: 'Supported',
  PARTIALLY_SUPPORTED: 'Partially Supported',
  MISLEADING: 'Misleading',
  UNSUPPORTED: 'Unsupported',
  CONTRADICTED: 'Contradicted',
  INSUFFICIENT_EVIDENCE: 'Insufficient Evidence'
};

const TEMPORAL_LABELS: Record<string, string> = {
  NOT_TIME_SENSITIVE: 'Not time-sensitive',
  CURRENT: 'Current evidence',
  HISTORICAL_OUTDATED: 'Possibly outdated',
  TIME_SENSITIVE_UNVERIFIED: 'Recency unverified'
};

@Component({
  selector: 'app-claim-card',
  standalone: true,
  imports: [CommonModule, ConfidenceBadgeComponent, LoadingTimerComponent, DomainPipe, MarkdownPipe],
  templateUrl: './claim-card.component.html'
})
export class ClaimCardComponent {
  readonly challengeEstimateSeconds = TIME_ESTIMATES.challenge;

  @Input({ required: true }) claim!: Claim;
  @Input() challenging = false;

  @Output() evidenceSelected = new EventEmitter<Evidence>();
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

  temporalLabel(status: string): string {
    return TEMPORAL_LABELS[status] ?? status;
  }

  temporalClass(status: string): string {
    return status === 'HISTORICAL_OUTDATED' || status === 'TIME_SENSITIVE_UNVERIFIED'
      ? 'bg-amber-50 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300 border-amber-200 dark:border-amber-800'
      : 'bg-surface-alt text-muted border-line';
  }
}

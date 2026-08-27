import { Component, EventEmitter, Input, OnDestroy, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ClaimService } from '../../core/claim.service';
import { AuditResponse, Claim, Evidence } from '../../core/models';
import { ClaimCardComponent } from '../claim-card/claim-card.component';
import { LoadingTimerComponent } from '../loading-timer/loading-timer.component';
import { estimateAuditSeconds } from '../../core/time-estimates';

const VERDICT_ORDER = ['SUPPORTED', 'PARTIALLY_SUPPORTED', 'MISLEADING', 'UNSUPPORTED', 'CONTRADICTED', 'INSUFFICIENT_EVIDENCE'];
const VERDICT_LABELS: Record<string, string> = {
  SUPPORTED: 'Supported',
  PARTIALLY_SUPPORTED: 'Partially supported',
  MISLEADING: 'Misleading',
  UNSUPPORTED: 'Unsupported',
  CONTRADICTED: 'Contradicted',
  INSUFFICIENT_EVIDENCE: 'Insufficient evidence'
};

@Component({
  selector: 'app-audit-panel',
  standalone: true,
  imports: [CommonModule, ClaimCardComponent, LoadingTimerComponent],
  templateUrl: './audit-panel.component.html'
})
export class AuditPanelComponent implements OnDestroy {
  @Input({ required: true }) documentId!: string;
  @Input() pageCount = 0;
  @Output() evidenceSelected = new EventEmitter<Evidence>();
  @Output() closed = new EventEmitter<void>();

  audit = signal<AuditResponse | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);
  challengingClaimId = signal<string | null>(null);
  verdictOrder = VERDICT_ORDER;
  verdictLabels = VERDICT_LABELS;

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  constructor(private claimService: ClaimService) {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  start(): void {
    this.loading.set(true);
    this.error.set(null);
    this.claimService.startAudit(this.documentId).subscribe({
      next: (res) => {
        this.audit.set(res);
        this.loading.set(false);
        this.beginPollingIfRunning();
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? 'Failed to start audit.');
      }
    });
  }

  refresh(): void {
    this.claimService.getAuditStatus(this.documentId).subscribe({
      next: (res) => {
        this.audit.set(res);
        this.beginPollingIfRunning();
      },
      error: () => {}
    });
  }

  challenge(claim: Claim): void {
    this.challengingClaimId.set(claim.id);
    this.claimService.challenge(this.documentId, claim.id).subscribe({
      next: (updated) => {
        this.challengingClaimId.set(null);
        this.audit.update((current) => (current ? { ...current, claims: [...current.claims, updated] } : current));
      },
      error: (err) => {
        this.challengingClaimId.set(null);
        this.error.set(err?.error?.message ?? 'Challenge failed.');
      }
    });
  }

  verdictCount(verdict: string): number {
    return this.audit()?.verdictCounts?.[verdict] ?? 0;
  }

  auditEstimateSeconds(): number {
    return estimateAuditSeconds(this.pageCount);
  }

  private beginPollingIfRunning(): void {
    if (this.audit()?.status === 'RUNNING' && !this.pollHandle) {
      this.pollHandle = setInterval(() => this.refresh(), 3000);
    } else if (this.audit()?.status !== 'RUNNING') {
      this.stopPolling();
    }
  }

  private stopPolling(): void {
    if (this.pollHandle) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }
}

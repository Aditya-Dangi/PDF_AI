import { Component, Input, OnChanges, OnDestroy, SimpleChanges, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

/** How far the bar creeps toward completion while still waiting - never 100%, so it never lies
 *  about being done before the operation actually finishes. */
const MAX_PROGRESS_PERCENT = 92;

/**
 * A spinner + label + live elapsed-time counter, with an optional estimated-progress bar above it,
 * for any operation whose duration isn't known upfront (LLM calls, web search, etc.) - as opposed
 * to PDF page rendering, which has a real "page X of Y" progress count and should keep showing that
 * instead. The bar is deliberately an *estimate*, not a promise: it eases toward ~92% over the
 * given estimatedSeconds using an ease-out curve (fast early progress, slowing down - matching how
 * these operations actually feel), then holds there until the real result arrives.
 */
@Component({
  selector: 'app-loading-timer',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './loading-timer.component.html'
})
export class LoadingTimerComponent implements OnChanges, OnDestroy {
  @Input() active = false;
  @Input() label = 'Working...';
  /** Rough expected duration in seconds for this specific operation, if known - drives the progress bar. */
  @Input() estimatedSeconds: number | null = null;

  elapsedSeconds = signal(0);
  progressPercent = signal(0);

  private intervalHandle: ReturnType<typeof setInterval> | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['active']) {
      if (this.active) {
        this.start();
      } else {
        this.stop();
      }
    }
  }

  ngOnDestroy(): void {
    this.stop();
  }

  formattedTime(): string {
    return this.formatSeconds(this.elapsedSeconds());
  }

  /** The estimate itself, formatted the same way as the elapsed counter, so it reads as directly
   *  comparable ("12s / ~15s est.") rather than making the user infer it from the bar's fill. */
  formattedEstimate(): string | null {
    if (!this.estimatedSeconds || this.estimatedSeconds <= 0) return null;
    return this.formatSeconds(Math.round(this.estimatedSeconds));
  }

  private formatSeconds(total: number): string {
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;
    return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`;
  }

  private start(): void {
    this.stop();
    this.elapsedSeconds.set(0);
    this.progressPercent.set(0);

    this.intervalHandle = setInterval(() => {
      this.elapsedSeconds.update((s) => s + 1);
      this.progressPercent.set(this.computeProgress(this.elapsedSeconds()));
    }, 1000);
  }

  private stop(): void {
    if (this.intervalHandle) {
      clearInterval(this.intervalHandle);
      this.intervalHandle = null;
    }
  }

  /** Ease-out toward MAX_PROGRESS_PERCENT: quick to start, asymptotically slows as elapsed time
   *  approaches (and then exceeds) the estimate, rather than a linear fill that would either finish
   *  "early" and sit at 100% while still waiting, or crawl at a constant rate that feels stalled. */
  private computeProgress(elapsed: number): number {
    if (!this.estimatedSeconds || this.estimatedSeconds <= 0) return 0;
    const ratio = elapsed / this.estimatedSeconds;
    const eased = 1 - Math.exp(-ratio * 1.4);
    return Math.min(MAX_PROGRESS_PERCENT, Math.round(eased * MAX_PROGRESS_PERCENT));
  }
}

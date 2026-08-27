import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SummaryResponse } from '../../core/models';
import { MarkdownPipe } from '../../core/markdown.pipe';
import { DurationPipe } from '../../core/duration.pipe';

/** Renders a plain "Summarize" result - deliberately much simpler than answer-card: just what was
 *  selected and its summary, no confidence badges, no evidence, no verdict/sources (that's what
 *  distinguishes Summarize from the fact-check pipeline - see SummarizationService on the backend). */
@Component({
  selector: 'app-summary-card',
  standalone: true,
  imports: [CommonModule, MarkdownPipe, DurationPipe],
  templateUrl: './summary-card.component.html'
})
export class SummaryCardComponent {
  @Input({ required: true }) summary!: SummaryResponse;
}

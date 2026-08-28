import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ThemeToggleComponent } from '../../components/theme-toggle/theme-toggle.component';
import { RevealOnScrollDirective } from '../../core/reveal-on-scroll.directive';

/**
 * Steps of the self-running hero demonstration.
 *
 * <p>The hero shows the product's actual loop rather than describing it: a question is asked, the
 * document is searched, an answer appears, and the exact passage it came from lights up on the
 * page. That last step is the whole point of the product, so it is the thing the landing page
 * spends its motion budget on.
 */
const DEMO_STEPS = ['idle', 'typing', 'searching', 'answering', 'citing'] as const;
type DemoStep = (typeof DEMO_STEPS)[number];

/** How long each step holds before advancing, in milliseconds. */
const STEP_DURATIONS: Record<DemoStep, number> = {
  idle: 600,
  typing: 1900,
  searching: 1400,
  answering: 1600,
  citing: 3600
};

const DEMO_QUESTION = 'What is the retry policy?';

@Component({
  selector: 'app-landing',
  standalone: true,
  imports: [CommonModule, RouterLink, ThemeToggleComponent, RevealOnScrollDirective],
  templateUrl: './landing.component.html'
})
export class LandingComponent implements OnInit, OnDestroy {
  readonly question = DEMO_QUESTION;

  step = signal<DemoStep>('idle');
  /** How much of the demo question has been "typed" so far. */
  typedLength = signal(0);

  private timer: ReturnType<typeof setTimeout> | null = null;
  private typingTimer: ReturnType<typeof setInterval> | null = null;
  private reducedMotion = false;

  constructor(private auth: AuthService) {}

  get isAuthenticated(): boolean {
    return this.auth.isAuthenticated();
  }

  typedQuestion(): string {
    return this.question.slice(0, this.typedLength());
  }

  /** True once the given step has been reached, so later stages stay on screen rather than
   *  flashing in and out as the loop advances. */
  atLeast(step: DemoStep): boolean {
    return DEMO_STEPS.indexOf(this.step()) >= DEMO_STEPS.indexOf(step);
  }

  ngOnInit(): void {
    this.reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

    if (this.reducedMotion) {
      // Show the finished state instead of looping: the demo's information is the end result, and
      // someone who asked for less motion should still get it.
      this.step.set('citing');
      this.typedLength.set(this.question.length);
      return;
    }
    this.advance('typing');
  }

  ngOnDestroy(): void {
    this.clearTimers();
  }

  private advance(step: DemoStep): void {
    this.step.set(step);

    if (step === 'typing') {
      this.runTyping();
    } else if (step === 'idle') {
      this.typedLength.set(0);
    }

    const next = DEMO_STEPS[(DEMO_STEPS.indexOf(step) + 1) % DEMO_STEPS.length];
    this.timer = setTimeout(() => this.advance(next), STEP_DURATIONS[step]);
  }

  private runTyping(): void {
    this.typedLength.set(0);
    const perCharacter = STEP_DURATIONS.typing / (this.question.length + 6);
    this.typingTimer = setInterval(() => {
      const next = this.typedLength() + 1;
      this.typedLength.set(next);
      if (next >= this.question.length && this.typingTimer) {
        clearInterval(this.typingTimer);
        this.typingTimer = null;
      }
    }, perCharacter);
  }

  private clearTimers(): void {
    if (this.timer) clearTimeout(this.timer);
    if (this.typingTimer) clearInterval(this.typingTimer);
  }
}

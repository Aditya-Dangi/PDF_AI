import { Directive, ElementRef, Input, OnDestroy, OnInit, inject } from '@angular/core';

/**
 * Fades an element up as it scrolls into view, once.
 *
 * <p>Uses IntersectionObserver rather than scroll listeners so the work happens off the main
 * thread's scroll path, and disconnects after the first reveal - an element that has already
 * appeared never needs watching again.
 *
 * <p>Honours prefers-reduced-motion by showing the element immediately: someone who has asked the
 * OS for less motion should get the content, not a static invisible div.
 */
@Directive({
  selector: '[appRevealOnScroll]',
  standalone: true
})
export class RevealOnScrollDirective implements OnInit, OnDestroy {
  /** Stagger, in milliseconds, so a group of siblings can cascade instead of all firing at once. */
  @Input('appRevealOnScroll') delayMs: number | string = 0;

  private readonly host = inject(ElementRef<HTMLElement>);
  private observer?: IntersectionObserver;

  ngOnInit(): void {
    const el = this.host.nativeElement as HTMLElement;

    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      el.classList.add('is-revealed');
      return;
    }

    el.classList.add('reveal');
    el.style.transitionDelay = `${Number(this.delayMs) || 0}ms`;

    this.observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          el.classList.add('is-revealed');
          this.observer?.disconnect();
        }
      },
      // A small negative bottom margin means the reveal fires just after the element genuinely
      // enters the viewport, rather than while it is still a sliver at the edge.
      { threshold: 0.15, rootMargin: '0px 0px -40px 0px' }
    );
    this.observer.observe(el);
  }

  ngOnDestroy(): void {
    this.observer?.disconnect();
  }
}

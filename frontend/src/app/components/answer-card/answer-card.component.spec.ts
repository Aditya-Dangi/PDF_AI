import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { AnswerCardComponent } from './answer-card.component';
import { AnswerResponse } from '../../core/models';

describe('AnswerCardComponent', () => {
  let fixture: ComponentFixture<AnswerCardComponent>;
  let component: AnswerCardComponent;

  const groundedAnswer: AnswerResponse = {
    messageId: 'msg-1',
    question: 'Where is the Eiffel Tower?',
    documentClaim: 'The Eiffel Tower is located in Paris, France, and was completed in 1889.',
    explanation: 'The document says the Eiffel Tower is in Paris and was completed in 1889.',
    insufficientContext: false,
    retrievalConfidence: 81.18,
    fidelityConfidence: 82.73,
    evidence: [
      {
        chunkId: 'chunk-1',
        page: 1,
        rects: [{ x: 72, y: 115.06, width: 444.17, height: 6.94 }],
        text: 'Claim two: The Eiffel Tower is located in Paris, France, and was completed in 1889.',
        similarity: 0.6235
      }
    ]
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AnswerCardComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(AnswerCardComponent);
    component = fixture.componentInstance;
    component.answer = groundedAnswer;
  });

  describe('verdict label/style mapping (pure logic)', () => {
    it('maps every verdict to a readable label', () => {
      expect(component.verdictLabel('SUPPORTED')).toBe('Supported');
      expect(component.verdictLabel('PARTIALLY_SUPPORTED')).toBe('Partially Supported');
      expect(component.verdictLabel('MISLEADING')).toBe('Misleading');
      expect(component.verdictLabel('UNSUPPORTED')).toBe('Unsupported');
      expect(component.verdictLabel('CONTRADICTED')).toBe('Contradicted');
      expect(component.verdictLabel('INSUFFICIENT_EVIDENCE')).toBe('Insufficient Evidence');
    });

    it('falls back to the raw string for an unrecognized verdict rather than showing blank', () => {
      expect(component.verdictLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    });

    it('gives supported and contradicted verdicts visually distinct styling', () => {
      expect(component.verdictClass('SUPPORTED')).toContain('green');
      expect(component.verdictClass('CONTRADICTED')).toContain('red');
      expect(component.verdictClass('SUPPORTED')).not.toEqual(component.verdictClass('CONTRADICTED'));
    });

    it('gives supporting and contradicting source stances visually distinct styling', () => {
      expect(component.stanceClass('SUPPORTS')).toContain('green');
      expect(component.stanceClass('CONTRADICTS')).toContain('red');
    });
  });

  describe('rendering', () => {
    it('shows the document claim and explanation for a grounded answer', () => {
      fixture.detectChanges();

      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('The Eiffel Tower is located in Paris');
      expect(text).toContain('Document Claim');
    });

    it('shows an "insufficient information" message instead of a claim when the document had no answer', () => {
      component.answer = {
        ...groundedAnswer,
        insufficientContext: true,
        documentClaim: 'The document does not address this question.',
        explanation: 'No relevant passage was found.'
      };
      fixture.detectChanges();

      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Not enough information');
      expect(text).not.toContain('Document Claim');
    });

    it('emits evidenceSelected with the clicked evidence item when an evidence button is clicked', () => {
      fixture.detectChanges();
      const emitted: unknown[] = [];
      component.evidenceSelected.subscribe((ev) => emitted.push(ev));

      const evidenceButton = fixture.debugElement.query(By.css('button'));
      evidenceButton.nativeElement.click();

      expect(emitted).toEqual([groundedAnswer.evidence[0]]);
    });

    it('offers a fact-check button when there is a claim and no fact-check yet, and emits on click', () => {
      fixture.detectChanges();
      let requested = false;
      component.factCheckRequested.subscribe(() => (requested = true));

      const buttons = fixture.debugElement.queryAll(By.css('button'));
      const factCheckButton = buttons.find((b) => b.nativeElement.textContent.includes('Fact-check'));
      expect(factCheckButton).withContext('expected a fact-check button to be rendered').toBeTruthy();

      factCheckButton!.nativeElement.click();
      expect(requested).toBeTrue();
    });

    it('does not offer a fact-check button once a fact-check result is already present', () => {
      component.factCheck = {
        messageId: 'msg-1',
        claimText: groundedAnswer.documentClaim,
        claimType: 'FACTUAL',
        checkable: true,
        verdict: 'SUPPORTED',
        webConfidence: 75.2,
        summary: 'Multiple reliable sources confirm this.',
        sources: []
      };
      fixture.detectChanges();

      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).not.toContain('Fact-check this claim');
      expect(text).toContain('Web Verification');
    });
  });
});

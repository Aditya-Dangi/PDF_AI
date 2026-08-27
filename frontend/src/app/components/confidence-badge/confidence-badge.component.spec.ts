import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConfidenceBadgeComponent } from './confidence-badge.component';

describe('ConfidenceBadgeComponent', () => {
  let fixture: ComponentFixture<ConfidenceBadgeComponent>;
  let component: ConfidenceBadgeComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ConfidenceBadgeComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(ConfidenceBadgeComponent);
    component = fixture.componentInstance;
    component.label = 'Document Retrieval';
  });

  function setValueAndDetect(value: number): void {
    component.value = value;
    fixture.detectChanges();
  }

  it('classifies a score of 70 or above as high confidence', () => {
    setValueAndDetect(70);
    expect(component.tier()).toBe('high');

    setValueAndDetect(95);
    expect(component.tier()).toBe('high');
  });

  it('classifies a score between 40 and 69.99 as medium confidence', () => {
    setValueAndDetect(40);
    expect(component.tier()).toBe('medium');

    setValueAndDetect(69.99);
    expect(component.tier()).toBe('medium');
  });

  it('classifies a score below 40 as low confidence', () => {
    setValueAndDetect(0);
    expect(component.tier()).toBe('low');

    setValueAndDetect(39.99);
    expect(component.tier()).toBe('low');
  });

  it('renders the numeric value and label text into the DOM', () => {
    setValueAndDetect(82.73);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Document Retrieval');
    expect(text).toContain('83%'); // toFixed(0) rounds 82.73 up
  });
});

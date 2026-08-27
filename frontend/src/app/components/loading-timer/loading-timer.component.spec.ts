import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { LoadingTimerComponent } from './loading-timer.component';

describe('LoadingTimerComponent', () => {
  let fixture: ComponentFixture<LoadingTimerComponent>;
  let component: LoadingTimerComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LoadingTimerComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(LoadingTimerComponent);
    component = fixture.componentInstance;
  });

  function setActive(value: boolean): void {
    component.active = value;
    component.ngOnChanges({ active: {} as any });
  }

  it('shows nothing while inactive', () => {
    setActive(false);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text.trim()).toBe('');
  });

  it('starts counting from 0 seconds as soon as it becomes active', fakeAsync(() => {
    component.label = 'Doing a thing...';
    setActive(true);
    fixture.detectChanges();

    expect(component.formattedTime()).toBe('0s');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Doing a thing...');
    expect(text).toContain('0s');

    setActive(false); // clear the interval before fakeAsync's own check for pending timers
  }));

  it('increments once per second while active', fakeAsync(() => {
    setActive(true);
    tick(3000);

    expect(component.elapsedSeconds()).toBe(3);
    expect(component.formattedTime()).toBe('3s');

    setActive(false);
  }));

  it('formats times over a minute as "Xm Ys"', fakeAsync(() => {
    setActive(true);
    tick(75000); // 1 minute 15 seconds

    expect(component.formattedTime()).toBe('1m 15s');

    setActive(false);
  }));

  it('resets to 0 each time it transitions from inactive to active', fakeAsync(() => {
    setActive(true);
    tick(5000);
    expect(component.elapsedSeconds()).toBe(5);

    setActive(false);
    setActive(true);

    expect(component.elapsedSeconds()).toBe(0);

    setActive(false);
  }));

  it('stops counting once it becomes inactive', fakeAsync(() => {
    setActive(true);
    tick(2000);
    expect(component.elapsedSeconds()).toBe(2);

    setActive(false);
    tick(5000);

    // No further increments after going inactive.
    expect(component.elapsedSeconds()).toBe(2);
  }));

  describe('progress bar (estimated time)', () => {
    it('stays at 0 when no estimate is given, regardless of elapsed time', fakeAsync(() => {
      component.estimatedSeconds = null;
      setActive(true);
      tick(10000);

      expect(component.progressPercent()).toBe(0);

      setActive(false);
    }));

    it('eases toward - but never all the way to - 100%, even long past the estimate', fakeAsync(() => {
      component.estimatedSeconds = 10;
      setActive(true);

      tick(5000); // half the estimate
      expect(component.progressPercent()).toBe(46);

      tick(5000); // right at the estimate
      expect(component.progressPercent()).toBe(69);

      tick(90000); // 10x the estimate - still short of promising 100% completion
      expect(component.progressPercent()).toBe(92);

      setActive(false);
    }));

    it('resets progress to 0 on each new activation, same as the elapsed timer', fakeAsync(() => {
      component.estimatedSeconds = 10;
      setActive(true);
      tick(10000);
      expect(component.progressPercent()).toBeGreaterThan(0);

      setActive(false);
      setActive(true);

      expect(component.progressPercent()).toBe(0);

      setActive(false);
    }));
  });
});

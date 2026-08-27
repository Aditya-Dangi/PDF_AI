import { Pipe, PipeTransform } from '@angular/core';

/** Formats a millisecond duration (e.g. how long an answer took to generate) as a short, human
 *  string - "7.3s" under a minute, "1m 12s" at or above it. Returns null (so the caller's @if
 *  hides the badge) for null/undefined/negative input, which happens for messages answered
 *  before this field existed. */
@Pipe({ name: 'duration', standalone: true })
export class DurationPipe implements PipeTransform {
  transform(ms: number | null | undefined): string | null {
    if (ms == null || ms < 0) return null;
    const totalSeconds = ms / 1000;
    if (totalSeconds < 60) return `${totalSeconds.toFixed(1)}s`;
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = Math.round(totalSeconds % 60);
    return `${minutes}m ${seconds}s`;
  }
}

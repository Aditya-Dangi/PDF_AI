import { Pipe, PipeTransform } from '@angular/core';

/** Extracts a bare, readable domain from a source URL (e.g. "https://www.angular.io/guide/x" ->
 *  "angular.io") - shown next to each source so "unknown authority" isn't the only thing a reader
 *  sees; the actual site is usually far more informative than the authority tier alone. */
@Pipe({ name: 'domain', standalone: true })
export class DomainPipe implements PipeTransform {
  transform(url: string): string {
    try {
      return new URL(url).hostname.replace(/^www\./, '');
    } catch {
      return url;
    }
  }
}

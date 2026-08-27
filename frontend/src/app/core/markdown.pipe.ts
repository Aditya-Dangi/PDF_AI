import { Pipe, PipeTransform, SecurityContext, inject } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { marked } from 'marked';

/** Renders AI-generated text (documentClaim/explanation/summary) as formatted markdown - tables,
 *  bullet lists, and bold text read far better for comparisons than a dense paragraph. Uses
 *  Angular's real sanitizer (not bypassSecurityTrustHtml) since this text can indirectly include
 *  content derived from external web pages via search results, not just the LLM's own words. */
@Pipe({ name: 'markdown', standalone: true })
export class MarkdownPipe implements PipeTransform {
  private sanitizer = inject(DomSanitizer);

  transform(text: string | null | undefined): string {
    if (!text) return '';
    const html = marked.parse(text, { async: false, gfm: true }) as string;
    return this.sanitizer.sanitize(SecurityContext.HTML, html) ?? '';
  }
}

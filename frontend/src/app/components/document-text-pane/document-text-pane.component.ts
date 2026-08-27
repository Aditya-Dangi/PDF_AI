import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { StructureService } from '../../core/structure.service';
import { DocumentBlock, StructureResponse } from '../../core/models';
import { MarkdownPipe } from '../../core/markdown.pipe';

type TextView = 'markdown' | 'json';

/**
 * The workspace's Text pane: the document's extracted content as readable Markdown, or as the
 * structured block JSON behind it.
 *
 * <p>Loads lazily - the backend computes structure on first request, which is slow on a large
 * document, so there is no reason to pay for it unless the user actually opens this tab.
 */
@Component({
  selector: 'app-document-text-pane',
  standalone: true,
  imports: [CommonModule, MarkdownPipe],
  templateUrl: './document-text-pane.component.html'
})
export class DocumentTextPaneComponent implements OnInit {
  @Input({ required: true }) documentId!: string;
  /** Emitted when a block is clicked, so the parent can highlight it on the rendered PDF using the
   *  same overlay path as answer evidence. */
  @Output() blockSelected = new EventEmitter<DocumentBlock>();

  view = signal<TextView>('markdown');
  structure = signal<StructureResponse | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);
  copied = signal(false);

  constructor(private structureService: StructureService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.structureService.getStructure(this.documentId).subscribe({
      next: (structure) => {
        this.loading.set(false);
        this.structure.set(structure);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err?.error?.message ?? 'Could not extract this document’s text.');
      }
    });
  }

  /** Header/footer blocks are excluded from the Markdown rendering, so the JSON view hides them
   *  too by default - showing them would make the two views look inconsistent for no benefit. */
  visibleBlocks(): DocumentBlock[] {
    return (this.structure()?.blocks ?? []).filter((b) => b.type !== 'HEADER_FOOTER');
  }

  blockCount(): number {
    return this.visibleBlocks().length;
  }

  jsonText(): string {
    return JSON.stringify(this.visibleBlocks(), null, 2);
  }

  currentText(): string {
    return this.view() === 'markdown' ? (this.structure()?.markdown ?? '') : this.jsonText();
  }

  copy(): void {
    navigator.clipboard?.writeText(this.currentText()).then(
      () => {
        this.copied.set(true);
        setTimeout(() => this.copied.set(false), 1500);
      },
      () => this.error.set('Could not copy to clipboard.')
    );
  }

  download(): void {
    const markdown = this.view() === 'markdown';
    const blob = new Blob([this.currentText()], {
      type: markdown ? 'text/markdown;charset=utf-8' : 'application/json;charset=utf-8'
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = markdown ? 'document.md' : 'document-blocks.json';
    link.click();
    // Revoking immediately can cancel the download in some browsers; one tick is enough for the
    // click to have been handled.
    setTimeout(() => URL.revokeObjectURL(url));
  }
}

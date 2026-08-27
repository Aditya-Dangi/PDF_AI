import { Component, Input, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-confidence-badge',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './confidence-badge.component.html'
})
export class ConfidenceBadgeComponent {
  @Input({ required: true }) label!: string;
  @Input({ required: true }) set value(v: number) {
    this._value.set(v);
  }

  private _value = signal(0);

  displayValue = computed(() => this._value());

  tier = computed<'high' | 'medium' | 'low'>(() => {
    const v = this._value();
    if (v >= 70) return 'high';
    if (v >= 40) return 'medium';
    return 'low';
  });
}

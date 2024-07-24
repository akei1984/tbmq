///
/// Copyright © 2016-2024 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { ChangeDetectorRef, Component, EventEmitter, Input, Output } from '@angular/core';
import { ClipboardService } from 'ngx-clipboard';
import { TooltipPosition } from '@angular/material/tooltip';
import { TranslateService } from '@ngx-translate/core';
import { ThemePalette } from '@angular/material/core';
import { coerceBoolean } from '@shared/decorators/coercion';

@Component({
  selector: 'tb-copy-button',
  styleUrls: ['copy-button.component.scss'],
  templateUrl: './copy-button.component.html'
})
export class CopyButtonComponent {

  private timer;

  copied = false;

  @Input()
  copyText: string;

  @Input()
  @coerceBoolean()
  disabled = false;

  @Input()
  mdiIcon: string;

  @Input()
  icon: string = 'content_copy';

  @Input()
  tooltipText: string = this.translate.instant('action.copy');

  @Input()
  tooltipPosition: TooltipPosition = 'above';

  @Input()
  style: {[key: string]: any} = {};

  @Input()
  color: ThemePalette;

  @Input()
  @coerceBoolean()
  miniButton = false;

  @Output()
  successCopied = new EventEmitter<string>();

  constructor(private clipboardService: ClipboardService,
              private translate: TranslateService,
              private cd: ChangeDetectorRef) {
  }

  copy($event: Event | string): void {
    if (typeof $event === 'object') {
      $event.stopPropagation();
    } else if ($event?.length) {
      this.copyText = $event;
    }
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.clipboardService.copy(this.copyText);
    this.successCopied.emit(this.copyText);
    this.copied = true;
    this.timer = setTimeout(() => {
      this.copied = false;
      this.cd.detectChanges();
    }, 1500);
  }

  get matTooltipText(): string {
    return this.copied ? this.translate.instant('action.on-copied') : this.tooltipText;
  }

  get matTooltipPosition(): TooltipPosition {
    return this.copied ? 'below' : this.tooltipPosition;
  }

}

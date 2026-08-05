/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {FunctionConfigurationComponent, FunctionConfigurationData} from '@valtimo/plugin';
import {BehaviorSubject, combineLatest, filter, map, Observable, Subscription, switchMap, take} from 'rxjs';
import {PublishCoworkerConfig} from '../../models';

@Component({
  standalone: false,
  selector: 'valtimo-publish-coworker-configuration',
  templateUrl: './publish-coworker-configuration.component.html',
  styleUrls: ['./publish-coworker-configuration.component.scss'],
})
export class PublishCoworkerConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<PublishCoworkerConfig>;
  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<FunctionConfigurationData> = new EventEmitter<FunctionConfigurationData>();

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<PublishCoworkerConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  ngOnInit(): void {
    this.openSaveSubscription();
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formValue: PublishCoworkerConfig): void {
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  private handleValid(formValue: PublishCoworkerConfig): void {
    // coworkerId is required; the request also needs a userPrompt or an expertiseId (+ input, validated server-side).
    const valid = !!formValue.coworkerId && (!!formValue.userPrompt || !!formValue.expertiseId);
    this.valid$.next(valid);
    this.valid.emit(valid);
  }

  private openSaveSubscription(): void {
    this.saveSubscription = this.save$
      ?.pipe(
        switchMap(() => combineLatest([this.formValue$, this.valid$]).pipe(take(1))),
        filter(([_, valid]) => valid),
        map(([formValue]) => formValue)
      )
      .subscribe(formValue => {
        this.configuration.emit(formValue!);
      });
  }
}

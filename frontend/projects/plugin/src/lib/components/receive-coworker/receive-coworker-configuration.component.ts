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
import {MultiInputKeyValue, SelectItem} from '@valtimo/components';
import {BehaviorSubject, combineLatest, filter, map, Observable, Subscription, switchMap, take} from 'rxjs';
import {COWORKER_EVENT_TYPES, ReceiveCoworkerConfig} from '../../models';

/** What `v-form` emits: the multi-input contributes key/value rows, not source/target. */
type ReceiveCoworkerFormValue = Omit<ReceiveCoworkerConfig, 'resultMappings'> & {
  resultMappings?: MultiInputKeyValue[];
};

@Component({
  standalone: false,
  selector: 'valtimo-receive-coworker-configuration',
  templateUrl: './receive-coworker-configuration.component.html',
  styleUrls: ['./receive-coworker-configuration.component.scss'],
})
export class ReceiveCoworkerConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<ReceiveCoworkerConfig>;
  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<FunctionConfigurationData> = new EventEmitter<FunctionConfigurationData>();

  readonly eventTypeItems: SelectItem[] = [
    {id: COWORKER_EVENT_TYPES.CHAT_RESPONSE, text: 'Chat response'},
    {id: COWORKER_EVENT_TYPES.CHAT_ERROR, text: 'Chat error'},
  ];

  /**
   * The saved mappings as the multi-input's key/value rows, resolved once per prefill.
   * Deliberately not a method call in the template: that would hand the multi-input a
   * new array on every change-detection run, and each one restarts its value stream.
   */
  readonly prefillMappings$ = new BehaviorSubject<MultiInputKeyValue[]>([]);

  private saveSubscription!: Subscription;
  private prefillSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<ReceiveCoworkerConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  ngOnInit(): void {
    this.openSaveSubscription();
    this.openPrefillSubscription();
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
    this.prefillSubscription?.unsubscribe();
  }

  formValueChange(formValue: ReceiveCoworkerFormValue): void {
    this.formValue$.next(this.toConfig(formValue));
    this.valid$.next(true);
    this.valid.emit(true);
  }

  private openPrefillSubscription(): void {
    this.prefillSubscription = this.prefillConfiguration$
      ?.pipe(map(prefill => prefill?.resultMappings ?? []))
      .subscribe(mappings => {
        this.prefillMappings$.next(mappings.map(mapping => ({key: mapping.source, value: mapping.target})));
      });
  }

  /**
   * The multi-input speaks key/value; the plugin action speaks source/target. Rows
   * that are only half filled in are dropped rather than saved as broken mappings.
   */
  private toConfig(formValue: ReceiveCoworkerFormValue): ReceiveCoworkerConfig {
    const {resultMappings, ...rest} = formValue;
    return {
      ...rest,
      resultMappings: (resultMappings ?? [])
        .filter(row => !!row.key && !!row.value)
        .map(row => ({source: row.key!, target: row.value!})),
    };
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

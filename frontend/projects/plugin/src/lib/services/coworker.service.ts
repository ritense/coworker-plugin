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

import {HttpClient} from '@angular/common/http';
import {Injectable} from '@angular/core';
import {ConfigService} from '@valtimo/shared';
import {Observable} from 'rxjs';
import {CoworkerOption} from '../models';

@Injectable({providedIn: 'root'})
export class CoworkerService {
  private readonly valtimoEndpointUri: string;

  constructor(
    private readonly http: HttpClient,
    configService: ConfigService
  ) {
    this.valtimoEndpointUri = configService.config.valtimoApi.endpointUri;
  }

  // Lists the coworkers of the given (saved) plugin configuration. The backend
  // resolves the configuration's CoWorker URL + credentials (incl. the secret
  // password) and proxies the call — the browser never sees the credentials.
  getCoworkers(pluginConfigurationId: string): Observable<CoworkerOption[]> {
    return this.http.get<CoworkerOption[]>(
      `${this.valtimoEndpointUri}management/v1/coworker/${pluginConfigurationId}/coworkers`
    );
  }
}

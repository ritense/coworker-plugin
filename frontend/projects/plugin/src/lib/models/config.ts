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

import {PluginConfigurationData} from '@valtimo/plugin';

/**
 * The closed set of CoWorker CloudEvent `type`s this plugin speaks.
 * Keep in lockstep with the backend `CoworkerEventType` enum.
 */
const COWORKER_EVENT_TYPES = {
  CHAT_REQUEST: 'nl.valtimo.coworker.chat-request',
  CHAT_RESPONSE: 'nl.valtimo.coworker.chat-response',
  CHAT_ERROR: 'nl.valtimo.coworker.chat-error',
} as const;

type CoworkerEventType = (typeof COWORKER_EVENT_TYPES)[keyof typeof COWORKER_EVENT_TYPES];

interface CoworkerConfig extends PluginConfigurationData {
  source: string;
  requestQueue: string;
  replyQueue: string;
  coworkerUrl?: string;
  coworkerUsername?: string;
  coworkerPassword?: string;
}

interface PublishCoworkerConfig {
  coworkerId?: string;
  userPrompt?: string;
  expertiseId?: string;
  input?: string;
  // Valtimo resource id of a file to send along, usually `pv:resourceId`.
  documentResourceId?: string;
}

// Same action properties as publish; the difference is the transport (synchronous REST, RabbitMQ fallback).
interface ChatCoworkerConfig {
  coworkerId?: string;
  userPrompt?: string;
  expertiseId?: string;
  input?: string;
}

/**
 * One line of the result mapping: `source` is a JSON pointer into the CoWorker's
 * answer, `target` a value-resolver expression (`pv:naam` or `doc:/pad`).
 */
interface CoworkerResultMapping {
  source: string;
  target: string;
}

interface ReceiveCoworkerConfig {
  eventType?: CoworkerEventType;
  resultMappings?: CoworkerResultMapping[];
}

// An option in the chat-coworker "Coworker" dropdown (returned by the plugin's
// management endpoint): id = the coworker UUID sent as coworkerId, name = label.
interface CoworkerOption {
  id: string;
  name: string;
}

export {
  COWORKER_EVENT_TYPES,
  ChatCoworkerConfig,
  CoworkerConfig,
  CoworkerEventType,
  CoworkerOption,
  CoworkerResultMapping,
  PublishCoworkerConfig,
  ReceiveCoworkerConfig,
};

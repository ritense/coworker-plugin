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

import { PluginSpecification } from "@valtimo/plugin";
import { CoworkerConfigurationComponent } from "./components/coworker-configuration/coworker-configuration.component";
import { PublishCoworkerConfigurationComponent } from "./components/publish-coworker/publish-coworker-configuration.component";
// chat-coworker (REST) disabled in v1 — see CoworkerPlugin.kt
// import { ChatCoworkerConfigurationComponent } from "./components/chat-coworker/chat-coworker-configuration.component";
import { ReceiveCoworkerConfigurationComponent } from "./components/receive-coworker/receive-coworker-configuration.component";
import { COWORKER_PLUGIN_LOGO_BASE64 } from "./assets";

const coworkerPluginSpecification: PluginSpecification = {
  pluginId: "coworker",
  pluginConfigurationComponent: CoworkerConfigurationComponent,
  pluginLogoBase64: COWORKER_PLUGIN_LOGO_BASE64,
  functionConfigurationComponents: {
    "publish-coworker": PublishCoworkerConfigurationComponent,
    // "chat-coworker": ChatCoworkerConfigurationComponent, // REST action disabled in v1
    "receive-coworker": ReceiveCoworkerConfigurationComponent,
  },
  pluginTranslations: {
    nl: {
      title: "Coworker",
      description: "Verstuur en ontvang Coworkers via RabbitMQ.",
      configurationTitle: "Configuratienaam",
      configurationTitleTooltip:
        "De naam van de huidige plugin-configuratie. Onder deze naam kan de configuratie in de rest van de applicatie teruggevonden worden.",
      "publish-coworker": "CoWorker vragen",
      "receive-coworker": "CoWorker-antwoord afwachten",
      source: "Bron (URN)",
      sourceTooltip: "De CloudEvent-bron als NL GOV URN, bijv. urn:nld:oin:<OIN>:systeem:coworker-plugin.",
      requestQueue: "Request queue",
      requestQueueTooltip: "De queue waarnaar chat-requests worden verstuurd (standaard vcs.chat.in).",
      replyQueue: "Reply queue",
      replyQueueTooltip: "De eigen reply-queue van deze plugin waarop chat-responses/-errors binnenkomen.",
      coworkerUrl: "Coworker URL",
      coworkerUrlTooltip: "Optionele URL van de CoWorker-server.",
      coworkerUsername: "Gebruikersnaam",
      coworkerUsernameTooltip: "Optionele gebruikersnaam voor HTTP Basic-authenticatie op de CoWorker REST-API.",
      coworkerPassword: "Wachtwoord",
      coworkerPasswordTooltip: "Optioneel wachtwoord voor HTTP Basic-authenticatie op de CoWorker REST-API.",
      receiveDescription:
        "Wacht op het CoWorker-antwoord (een chat-response of chat-error) op het verzoek dat eerder met 'CoWorker vragen' is verstuurd, en hervat deze processtap zodra het bijbehorende antwoord binnenkomt. Kies hieronder optioneel op welk event type deze stap reageert; laat leeg om op elk antwoord te reageren.",
      eventType: "Event type",
      publishCoworkerId: "Coworker ID",
      publishCoworkerIdTooltip: "De Coworker die dit verzoek verwerkt. Ondersteunt value resolvers.",
      userPrompt: "Gebruikersprompt",
      userPromptTooltip: "Vrije tekst voor de chat. Vereist tenzij een expertise wordt gebruikt. Ondersteunt value resolvers.",
      expertiseId: "Expertise ID",
      expertiseIdTooltip: "Optioneel: expertise voor gestructureerde verwerking. Ondersteunt value resolvers.",
      input: "Input",
      inputTooltip: "Optionele JSON-input voor expertise-verwerking. Ondersteunt value resolvers.",
      receiveEventTypeTooltip: "Het Coworker-event waarop deze stap reageert.",
    },
    en: {
      title: "Coworker",
      description: "Send and receive Coworkers via RabbitMQ.",
      configurationTitle: "Configuration name",
      configurationTitleTooltip:
        "The name of the current plugin configuration. Under this name, the configuration can be found in the rest of the application.",
      "publish-coworker": "Ask CoWorker",
      "receive-coworker": "Await CoWorker reply",
      source: "Source (URN)",
      sourceTooltip: "The CloudEvent source as an NL GOV URN, e.g. urn:nld:oin:<OIN>:systeem:coworker-plugin.",
      requestQueue: "Request queue",
      requestQueueTooltip: "The queue chat-requests are sent to (default vcs.chat.in).",
      replyQueue: "Reply queue",
      replyQueueTooltip: "This plugin's own reply queue where chat-responses/-errors arrive.",
      coworkerUrl: "Coworker URL",
      coworkerUrlTooltip: "Optional URL of the CoWorker server.",
      coworkerUsername: "Username",
      coworkerUsernameTooltip: "Optional username for HTTP Basic auth on the CoWorker REST API.",
      coworkerPassword: "Password",
      coworkerPasswordTooltip: "Optional password for HTTP Basic auth on the CoWorker REST API.",
      receiveDescription:
        "Waits for the CoWorker reply (a chat-response or chat-error) to the request sent earlier by 'Ask CoWorker', and resumes this process step once the matching reply arrives. Optionally choose below which event type this step reacts to; leave empty to react to any reply.",
      eventType: "Event type",
      publishCoworkerId: "Coworker ID",
      publishCoworkerIdTooltip: "The Coworker that processes this request. Supports value resolvers.",
      userPrompt: "User prompt",
      userPromptTooltip: "Free-text message for the chat. Required unless an expertise is used. Supports value resolvers.",
      expertiseId: "Expertise ID",
      expertiseIdTooltip: "Optional: expertise for structured processing. Supports value resolvers.",
      input: "Input",
      inputTooltip: "Optional JSON input for expertise processing. Supports value resolvers.",
      receiveEventTypeTooltip: "The Coworker event this step reacts to.",
    },
  },
};

export { coworkerPluginSpecification };

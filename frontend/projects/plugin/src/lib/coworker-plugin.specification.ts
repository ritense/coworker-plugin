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
      description: "Communiceer met Coworker Server via RabbitMQ.",
      configurationTitle: "Configuratienaam",
      configurationTitleTooltip:
        "De naam van de huidige plugin-configuratie. Onder deze naam kan de configuratie in de rest van de applicatie teruggevonden worden.",
      "publish-coworker": "CoWorker vragen",
      "receive-coworker": "CoWorker-antwoord afwachten",
      rabbitMqSection: "RabbitMQ — berichtenverkeer",
      rabbitMqSectionDescription:
        "De message broker waarover vragen worden verstuurd en antwoorden binnenkomen. Host, TLS, poort, " +
        "virtual host en inloggegevens zijn optioneel: laat ze leeg om de broker-instellingen van de " +
        "applicatie zelf te gebruiken.",
      coworkerApiSection: "CoWorker-server — REST-API",
      coworkerApiSectionDescription:
        "De REST-API van de CoWorker-server. Optioneel: het chatverkeer zelf loopt altijd via RabbitMQ en " +
        "gebruikt deze API niet. Deze velden horen bij de REST-functies, die in deze versie uitgeschakeld " +
        "zijn, en kunnen dus leeg blijven.",
      source: "RabbitMQ-bron (URN)",
      sourceTooltip:
        "De CloudEvent-bron waarmee dit systeem zich identificeert in elk RabbitMQ-bericht, als NL GOV URN, " +
        "bijv. urn:nld:oin:<OIN>:systeem:coworker-plugin.",
      requestQueue: "RabbitMQ request queue",
      requestQueueTooltip:
        "De RabbitMQ-queue waarnaar chat-requests worden verstuurd (standaard vcs.chat.in).",
      replyQueue: "RabbitMQ reply queue",
      replyQueueTooltip:
        "De eigen RabbitMQ-reply-queue van deze plugin waarop chat-responses/-errors binnenkomen. " +
        "De plugin luistert automatisch op deze queue.",
      coworkerUrl: "CoWorker API-URL",
      coworkerUrlTooltip: "Optionele URL van de REST-API van de CoWorker-server (dus niet van RabbitMQ).",
      coworkerUsername: "CoWorker API-gebruikersnaam",
      coworkerUsernameTooltip:
        "Optionele gebruikersnaam voor HTTP Basic-authenticatie op de CoWorker REST-API. " +
        "Dit is niet de RabbitMQ-gebruikersnaam.",
      coworkerPassword: "CoWorker API-wachtwoord",
      coworkerPasswordTooltip:
        "Optioneel wachtwoord voor HTTP Basic-authenticatie op de CoWorker REST-API. " +
        "Dit is niet het RabbitMQ-wachtwoord.",
      rabbitMqHost: "RabbitMQ-host",
      rabbitMqHostTooltip:
        "Optioneel: de host van de RabbitMQ-broker. Laat leeg om de instelling van de applicatie " +
        "(spring.rabbitmq.host) te gebruiken.",
      rabbitMqSslEnabled: "TLS (amqps) gebruiken",
      rabbitMqSslEnabledTooltip:
        "Verbind via TLS (amqps) in plaats van gewoon amqp. Laat uit staan om de instelling van de applicatie " +
        "te volgen. Let op: alleen poort 5671 invullen zet TLS niet aan. Laat de poort leeg om automatisch " +
        "5671 (met TLS) of 5672 (zonder) te gebruiken.",
      rabbitMqPort: "RabbitMQ-poort",
      rabbitMqPortTooltip:
        "Optioneel: de poort van de RabbitMQ-broker (standaard 5672). Laat leeg om de instelling van de " +
        "applicatie te gebruiken.",
      rabbitMqVirtualHost: "RabbitMQ virtual host",
      rabbitMqVirtualHostTooltip:
        "Optioneel: de virtual host op de broker (standaard /). Laat leeg om de instelling van de applicatie " +
        "te gebruiken.",
      rabbitMqUsername: "RabbitMQ-gebruikersnaam",
      rabbitMqUsernameTooltip:
        "Optionele gebruikersnaam voor de RabbitMQ-broker, gebruikt voor zowel het versturen van vragen als " +
        "het ontvangen van antwoorden. Dit is niet de CoWorker API-gebruikersnaam. Laat leeg om de " +
        "instelling van de applicatie te gebruiken.",
      rabbitMqPassword: "RabbitMQ-wachtwoord",
      rabbitMqPasswordTooltip:
        "Wachtwoord bij de RabbitMQ-gebruikersnaam, niet bij de CoWorker API-gebruikersnaam. Vul dit samen " +
        "met de gebruikersnaam in; de applicatie-instelling wordt niet als terugval gebruikt zodra er een " +
        "gebruikersnaam is ingevuld.",
      receiveDescription:
        "Wacht op het CoWorker-antwoord (een chat-response of chat-error) op het verzoek dat eerder met 'CoWorker vragen' is verstuurd, en hervat deze processtap zodra het bijbehorende antwoord binnenkomt. Kies hieronder optioneel op welk event type deze stap reageert; laat leeg om op elk antwoord te reageren.",
      eventType: "Event type",
      publishCoworkerId: "Coworker ID",
      publishCoworkerIdTooltip: "De Coworker die dit verzoek verwerkt. Ondersteunt value resolvers.",
      userPrompt: "Gebruikersprompt",
      userPromptTooltip:
        "Vrije tekst voor de chat. Vereist tenzij een expertise wordt gebruikt. Gebruik {{pv:variabele}} of " +
        "{{doc:/pad}} om zaakgegevens in de tekst te verwerken, bijv. 'Beoordeel {{doc:/vraag}} op spoed'.",
      expertiseId: "Expertise ID",
      expertiseIdTooltip: "Optioneel: expertise voor gestructureerde verwerking. Ondersteunt value resolvers.",
      documentResourceId: "Document",
      documentResourceIdTooltip:
        "Optioneel: het Valtimo resource-id van een bestand dat met de vraag wordt meegestuurd, " +
        "meestal pv:resourceId. Maximaal 10 MB (instelbaar).",
      input: "Input",
      inputTooltip: "Optionele JSON-input voor expertise-verwerking. Ondersteunt value resolvers.",
      receiveEventTypeTooltip: "Het Coworker-event waarop deze stap reageert.",
      resultMappings: "Antwoord verwerken",
      resultMappingsTooltip:
        "Optioneel: haal velden uit een JSON-antwoord en zet ze in een procesvariabele of het zaakdossier. " +
        "Werkt alleen als de Coworker JSON antwoordt — het beste via een antwoordschema op de Coworker zelf, " +
        "anders door er in de prompt om te vragen.",
      resultMappingSource: "Veld in antwoord (bijv. /nettoBedrag)",
      resultMappingTarget: "Doel (pv:naam of doc:/pad)",
      resultMappingAddRow: "Regel toevoegen",
    },
    en: {
      title: "Coworker",
      description: "Communicate with Coworker Server via RabbitMQ.",
      configurationTitle: "Configuration name",
      configurationTitleTooltip:
        "The name of the current plugin configuration. Under this name, the configuration can be found in the rest of the application.",
      "publish-coworker": "Ask CoWorker",
      "receive-coworker": "Await CoWorker reply",
      rabbitMqSection: "RabbitMQ — messaging",
      rabbitMqSectionDescription:
        "The message broker questions are sent over and answers arrive on. Host, TLS, port, virtual host " +
        "and credentials are all optional: leave them empty to use the application's own broker settings.",
      coworkerApiSection: "CoWorker server — REST API",
      coworkerApiSectionDescription:
        "The CoWorker server's REST API. Optional: the chat traffic itself always goes over RabbitMQ and " +
        "never uses this API. These fields belong to the REST-based features, which are disabled in this " +
        "version, so they can be left empty.",
      source: "RabbitMQ source (URN)",
      sourceTooltip:
        "The CloudEvent source this system identifies itself with on every RabbitMQ message, as an NL GOV " +
        "URN, e.g. urn:nld:oin:<OIN>:systeem:coworker-plugin.",
      requestQueue: "RabbitMQ request queue",
      requestQueueTooltip: "The RabbitMQ queue chat-requests are sent to (default vcs.chat.in).",
      replyQueue: "RabbitMQ reply queue",
      replyQueueTooltip:
        "This plugin's own RabbitMQ reply queue where chat-responses/-errors arrive. The plugin listens " +
        "on it automatically.",
      coworkerUrl: "CoWorker API URL",
      coworkerUrlTooltip: "Optional URL of the CoWorker server's REST API (not of RabbitMQ).",
      coworkerUsername: "CoWorker API username",
      coworkerUsernameTooltip:
        "Optional username for HTTP Basic auth on the CoWorker REST API. This is not the RabbitMQ username.",
      coworkerPassword: "CoWorker API password",
      coworkerPasswordTooltip:
        "Optional password for HTTP Basic auth on the CoWorker REST API. This is not the RabbitMQ password.",
      rabbitMqHost: "RabbitMQ host",
      rabbitMqHostTooltip:
        "Optional: the RabbitMQ broker host. Leave empty to use the application's own setting " +
        "(spring.rabbitmq.host).",
      rabbitMqSslEnabled: "Use TLS (amqps)",
      rabbitMqSslEnabledTooltip:
        "Connect over TLS (amqps) instead of plain amqp. Leave off to follow the application's own setting. " +
        "Note: setting the port to 5671 does not enable TLS by itself. Leave the port empty to use 5671 " +
        "(with TLS) or 5672 (without) automatically.",
      rabbitMqPort: "RabbitMQ port",
      rabbitMqPortTooltip:
        "Optional: the RabbitMQ broker port (5672 by default). Leave empty to use the application's own setting.",
      rabbitMqVirtualHost: "RabbitMQ virtual host",
      rabbitMqVirtualHostTooltip:
        "Optional: the virtual host on the broker (/ by default). Leave empty to use the application's own setting.",
      rabbitMqUsername: "RabbitMQ username",
      rabbitMqUsernameTooltip:
        "Optional username for the RabbitMQ broker, used both for sending questions and for receiving replies. " +
        "This is not the CoWorker API username. Leave empty to use the application's own setting.",
      rabbitMqPassword: "RabbitMQ password",
      rabbitMqPasswordTooltip:
        "Password belonging to the RabbitMQ username, not to the CoWorker API username. Fill it in together " +
        "with the username: once a username is set, the application's setting is no longer used as a fallback.",
      receiveDescription:
        "Waits for the CoWorker reply (a chat-response or chat-error) to the request sent earlier by 'Ask CoWorker', and resumes this process step once the matching reply arrives. Optionally choose below which event type this step reacts to; leave empty to react to any reply.",
      eventType: "Event type",
      publishCoworkerId: "Coworker ID",
      publishCoworkerIdTooltip: "The Coworker that processes this request. Supports value resolvers.",
      userPrompt: "User prompt",
      userPromptTooltip:
        "Free-text message for the chat. Required unless an expertise is used. Use {{pv:variable}} or " +
        "{{doc:/path}} to weave case data into the text, e.g. 'Assess {{doc:/question}} for urgency'.",
      expertiseId: "Expertise ID",
      expertiseIdTooltip: "Optional: expertise for structured processing. Supports value resolvers.",
      documentResourceId: "Document",
      documentResourceIdTooltip:
        "Optional: the Valtimo resource id of a file to send along with the question, " +
        "usually pv:resourceId. Maximum 10 MB (configurable).",
      input: "Input",
      inputTooltip: "Optional JSON input for expertise processing. Supports value resolvers.",
      receiveEventTypeTooltip: "The Coworker event this step reacts to.",
      resultMappings: "Process the answer",
      resultMappingsTooltip:
        "Optional: take fields out of a JSON answer and put them in a process variable or the case document. " +
        "Only works when the CoWorker answers in JSON — best arranged with a response schema on the CoWorker " +
        "itself, otherwise by asking for it in the prompt.",
      resultMappingSource: "Field in answer (e.g. /nettoBedrag)",
      resultMappingTarget: "Target (pv:name or doc:/path)",
      resultMappingAddRow: "Add row",
    },
  },
};

export { coworkerPluginSpecification };

# Coworker Plugin

Lets a BPMN process ask a **CoWorker** (an AI assistant) a question and use the
answer in the process.

Ask the CoWorker a question with **Ask CoWorker** and receive the answer later with
**Await CoWorker reply** — the process waits in the meantime.

## Configuration

Create a plugin configuration and fill in:

| Field              | Required | Description                                                                                                 |
|--------------------|----------|-------------------------------------------------------------------------------------------------------------|
| Configuration name | yes      | A name to recognise this configuration elsewhere in the app.                                                |
| Source (URN)       | yes      | Identifies this system to the CoWorker server, e.g. `urn:nld:oin:<OIN>:systeem:coworker-plugin`.            |
| Request queue      | yes      | Where questions are sent (default `vcs.chat.in`). Used by **Ask CoWorker**.                                 |
| Reply queue        | yes      | Where answers come back. Used by **Await CoWorker reply**.                                                  |
| Coworker URL       | no       | Address of the CoWorker server.                                                                             |
| Username           | no       | Login name for the CoWorker server (if it requires one).                                                    |
| Password           | no       | Password for the CoWorker server (stored securely).                                                         |

## Actions

### Ask CoWorker

Sends a question and lets the process continue; the answer arrives later. Place an
**Await CoWorker reply** step further on to receive it.

Use on a **Send Task** or **Intermediate Throw Event**.

| Field        | Description                                                         |
|--------------|---------------------------------------------------------------------|
| Coworker ID  | Which CoWorker should answer. Supports value resolvers.             |
| User prompt  | The question / instruction. Required unless you use an expertise. Supports placeholders — see below. |
| Expertise ID | Optional. Use a predefined expertise instead of a free-text prompt. |
| Document     | Optional. A file to send along — see below.                         |
| Input        | Optional JSON input for the expertise.                              |

#### Sending a document along

Fill **Document** with the resource id of a file already present in Valtimo — almost
always `pv:resourceId`, the process variable that a form upload or the Documenten API
plugin's download action leaves behind. The file travels with the question, so you can
ask things like *"lees deze factuur en geef het netto-, btw- en totaalbedrag"*.

- One document per step. Leave the field empty to send no document.
- The file is base64-encoded into the message, so it is capped at **10 MB** by default
  (`valtimo.coworker.max-document-size`). A larger file fails the step.
- A missing, unreadable or empty resource fails the step as well.

#### Using case data in the prompt

The **User prompt** may contain `{{...}}` placeholders that are filled with case data
before the question is sent:

```
Beoordeel {{doc:/vraag}} op spoed. Klantnummer: {{pv:klantnummer}}
```

| Placeholder      | Value                                             |
|------------------|---------------------------------------------------|
| `{{pv:naam}}`    | The process variable `naam`.                      |
| `{{doc:/a/b}}`   | Field `/a/b` from the case document (JSON).       |

Any value resolver available in your Valtimo installation can be used this way.
Numbers and booleans are inserted as text; objects and lists as JSON.

Two things to know:

- A placeholder that cannot be resolved (unknown field, empty variable) **fails the
  step** instead of sending a prompt with a hole in it. Guard optional data in your
  process, or leave it out of the prompt.
- Only `{{prefix:key}}` is treated as a placeholder, so a prompt can still contain
  ordinary braces — `{{"netto": 0}}` is sent as-is.

Note that the other fields (Coworker ID, Expertise ID, Input) work differently: they
support a value resolver as the **whole** field value (`pv:klantnummer`), not inside a
sentence.

### Await CoWorker reply

Receives the answer to an earlier **Ask CoWorker** step and continues the process.

Use on a **Receive Task** or **Intermediate Catch Event (Message)**.

| Field      | Description                                                                    |
|------------|--------------------------------------------------------------------------------|
| Event type | Which reply to react to: *chat response* or *chat error*. Leave empty for any. |
| Process the answer | Optional. Put fields from a JSON answer straight into process variables or the case document — see below. |

#### Putting the answer into variables and the case document

When the CoWorker answers with JSON, each row of **Process the answer** takes one
field out of it and writes it somewhere:

| Field in answer | Target                |
|-----------------|-----------------------|
| `/nettoBedrag`  | `doc:/factuur/netto`  |
| `/btwBedrag`    | `doc:/factuur/btw`    |
| `/totaalBedrag` | `pv:totaalbedrag`     |

- **Field in answer** is a JSON pointer; nested fields work (`/adres/straat`) and the
  leading `/` may be left out.
- **Target** is either `pv:naam` (a process variable) or `doc:/pad` (a field in the
  case document).
- Numbers and booleans keep their type; objects and arrays are written as JSON text.

The CoWorker has to answer in JSON for this to work. The reliable way is to configure
the coworker itself with a response schema on the CoWorker server — it then always
answers in that shape, and the field names in your mapping are simply the ones from
that schema. Asking for JSON in the prompt (*"Antwoord uitsluitend als JSON met de
velden nettoBedrag, btwBedrag en totaalBedrag"*) also works, but a schema is the
sturdier route. A ```json code fence around the answer is unwrapped automatically.

If the answer is not JSON, or a field is missing, the step **still continues** — it
would be worse to leave the process stuck. What went wrong is put in the
`coworkerMappingError` process variable, so you can branch on it. Document values are
written before the process moves on, so the next step sees them.

## Using the answer

After the answer is received (on the step after **Await CoWorker reply**), these
process variables are available:

| Variable            | Description                                     |
|---------------------|-------------------------------------------------|
| `coworkerContent`   | The CoWorker's answer.                          |
| `coworkerSuccess`   | `true` on success, `false` on error.            |
| `coworkerType`      | Whether it was a chat response or a chat error. |
| `coworkerError`     | The error message (only on failure).            |
| `coworkerErrorCode` | The error code (only on failure).               |
| `coworkerMappingError` | Why **Process the answer** did not fully succeed (only when it didn't). |

You can branch on `coworkerSuccess` (e.g. an exclusive gateway) to handle errors.

## Multiple chats at once

You can run several CoWorker chats in parallel branches of the same process (or in a
multi-instance loop). Each branch keeps its **own** question and answer, so they don't
interfere. If you need a branch's answer after the branches join back together, copy
it to another variable with a BPMN output mapping on the step.

## Sessions (multi-turn conversations)

Each chat is currently **standalone** — the plugin does not yet carry a conversation
across multiple steps, so a later question doesn't automatically "remember" an earlier
one. (The CoWorker server itself supports multi-turn sessions; wiring that through the
plugin is planned — see the technical reference.)

---

For administrators and developers — application settings, how correlation and retries
work, the coworker list endpoint, and database tables — see the
[technical reference](technical-reference.md).

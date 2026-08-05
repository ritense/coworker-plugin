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
| User prompt  | The question / instruction. Required unless you use an expertise.   |
| Expertise ID | Optional. Use a predefined expertise instead of a free-text prompt. |
| Input        | Optional JSON input for the expertise.                              |

### Await CoWorker reply

Receives the answer to an earlier **Ask CoWorker** step and continues the process.

Use on a **Receive Task** or **Intermediate Catch Event (Message)**.

| Field      | Description                                                                    |
|------------|--------------------------------------------------------------------------------|
| Event type | Which reply to react to: *chat response* or *chat error*. Leave empty for any. |

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

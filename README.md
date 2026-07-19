# Interactive Input

> A notification bell and a rich human‑in‑the‑loop (HITL) modal for Jenkins pipelines that pause for a human decision — plus a language‑agnostic REST API so any external agent (a bot, a script, an AI copilot) can answer on a human's behalf.

[![Jenkins](https://img.shields.io/badge/Jenkins-2.555.2%2B-d24939?logo=jenkins&logoColor=white)](https://www.jenkins.io/)
[![Java](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-2ea44f.svg)](LICENSE)
[![Pipeline](https://img.shields.io/badge/Pipeline-durable-3f7cac.svg)](https://www.jenkins.io/doc/book/pipeline/)
[![JCasC](https://img.shields.io/badge/JCasC-ready-6f42c1.svg)](https://www.jenkins.io/projects/jcasc/)

---

## Table of contents

- [Why this plugin exists](#why-this-plugin-exists)
- [`input` vs `interactive-input`](#input-vs-interactive-input)
- [Features](#features)
- [How it works](#how-it-works)
- [Install](#install)
- [Quick start](#quick-start)
- [The `askInteractive` step](#the-askinteractive-step)
- [Human-in-the-loop scenarios](#human-in-the-loop-scenarios)
- [Drive it from an AI agent (Python)](#drive-it-from-an-ai-agent-python)
- [REST API](#rest-api)
- [Bridging existing `input` steps](#bridging-existing-input-steps)
- [Settings and screens](#settings-and-screens)
- [Configuration (UI + JCasC)](#configuration-ui--jcasc)
- [Security model](#security-model)
- [Language applicability & restrictions](#language-applicability--restrictions)
- [Compatibility matrix](#compatibility-matrix)
- [Build from source](#build-from-source)
- [Project docs](#project-docs)
- [License](#license)

---

## Why this plugin exists

Jenkins has shipped a pipeline `input` step for years. It works, but it has two long‑standing gaps for teams doing serious human‑in‑the‑loop automation:

1. **There is no in‑UI signal that a build is waiting for you.** A paused build sits silently until someone happens to open the right build page. Approvers miss deploys; pipelines idle for hours against their will.
2. **The approval surface is minimal.** The built‑in prompt is a message, an OK button, and optional form parameters. There is no place for rich context (release notes, a diff, a risk summary), no notion of *why* each choice exists, and no first‑class way for an **external agent** to answer programmatically with a clean, versioned contract.

`interactive-input` closes both gaps **without changing anything about how your existing pipelines behave**. It adds a notification bell, a rich modal, a durable `askInteractive` step, and a REST API — all opt‑in, all governed by the same permission model Jenkins already enforces on `input`.

---

## `input` vs `interactive-input`

Both pause a pipeline and wait for a human. Here is what changes:

| Capability | Built‑in `input` | `interactive-input` |
|---|---|---|
| Pause a pipeline for a human decision | ✅ | ✅ (`askInteractive`) |
| **Per‑project notification centre** | ❌ | ✅ job‑page box + page, build‑history "awaiting input" badge, per‑build audit view |
| **In‑UI notification bell** | ❌ (silent until you open the build) | ✅ opt‑in global bell, header‑anchored, **context‑scoped** (dashboard = all answerable, inside a pipeline = that pipeline only), polled |
| **Shows who started the build** | ❌ | ✅ "started by &lt;user&gt;" on every surface |
| **Anchored console audit link** | ⚠️ links to the input form | ✅ links to a full audit view (what was shown + what was chosen) + "Paused" flow marker |
| **Rich modal** (context panel, per‑choice rationale) | ❌ message + OK only | ✅ Markdown context (expanded by default), choices with a "why", free‑text w/ live preview |
| **Structured choices with rationale** | ⚠️ via form parameters only | ✅ `[id, label, why]` first‑class |
| **Versioned JSON REST API** for external agents | ❌ (internal Stapler form POST) | ✅ `/interactive-input/api/v1/**` |
| **Answer from a script / bot / AI agent** | ⚠️ brittle (scrape crumb + form) | ✅ documented `POST …/answer` contract |
| **SLA / auto‑expiry** | ❌ waits forever (unless you code a `timeout{}`) | ✅ per‑step `slaMinutes` + global default |
| **Surfaces *existing* `input` steps** | n/a | ✅ opt‑in `inputStepBridge` (no pipeline edits) |
| **Safe Markdown rendering** | n/a | ✅ server‑side escaped (no raw HTML/script) |
| **JCasC configuration** | partial | ✅ every capability across `unclassified.interactiveInput` (functional) + `appearance.interactiveInputAppearance` (surfaces) |
| Durable across controller restart | ✅ | ✅ (same durable‑step foundation) |
| Permission model | Item.BUILD / submitter | ✅ **identical** (mirrors `pipeline-input-step`) |
| Runtime AI dependency | n/a | ❌ none — the API is generic HITL plumbing |

**TL;DR** — `interactive-input` is a *superset UX and an integration surface* on top of the same durable, permission‑checked foundation as `input`. You can adopt it incrementally: flip on the bridge to light up existing inputs, or write new `askInteractive` steps when you want the richer surface.

---

## Features

- 📍 **Per‑project notification centre** — notifications surface *where the work is*, not at one Jenkins‑wide point: a box + sidebar page on each pipeline/job listing its pending questions, an "awaiting input" badge next to the relevant build in the build‑history list, and a per‑build audit view. On by default (`perProjectCentre`, under **Appearance**). The inline job‑page box has its **own** on/off switch (`jobPageBox`, on by default) so you can keep the badge + sidebar without the big box.
- 👁️ **Attention pulse** — the build‑history "awaiting input" badge and the job‑page box title **blink slowly in red** to catch the eye, with a `prefers-reduced-motion` fallback that disables the animation for motion‑sensitive users.
- 🎛️ **Choosable notification icon** — pick the icon used across the bell, badge and sidebar from eight meaning‑matched Ionicons (speech bubble *(default)*, raised hand, person, pull‑request, megaphone, hourglass, alert, classic bell) under **Appearance**.
- 👤 **Attribution** — every surface shows **who started the build** ("started by &lt;user&gt;", or `scm`/`timer`/`upstream`/`system`), so reviewers can tell whose job is waiting.
- 🔗 **Console audit link** — like the built‑in `input`, the build log gets an anchored link at the point of invocation; clicking it opens the audit view showing what was displayed and what was chosen. The flow node is also marked **Paused** so stage/flow views reflect the wait, and the outcome (answered/aborted/expired, by whom) is logged.
- 🔔 **Global notification bell** — an optional header badge with the count of questions *you* can answer, polled at a configurable cadence (no WebSocket/SSE, so it works through every corporate proxy). **Context‑scoped**: on the dashboard it lists **every** answerable question; inside a pipeline (a job/build page) it narrows to **that pipeline's** questions. **Off by default** (`notificationCentre`, under **Appearance**); anchored into the header controls (with a bottom‑right floating fallback) so it never overlaps the settings gear.
- 🪟 **Rich modal** — Markdown context panel (**expanded by default**), radio choices each with an optional rationale, optional free‑text with a live (server‑sanitised) preview, full keyboard/focus‑trap accessibility. Shared by the bell and every per‑project surface.
- 📨 **Per‑pipeline notification preferences** — a *Configure* section (email/Teams/recipients/webhook) that persists intent now; delivery ships in a future release.
- 🧩 **`askInteractive` step** — a durable pipeline step that returns the chosen id (or free text), throws on abort, and times out on SLA.
- 🌐 **Versioned REST API** — `GET/POST` JSON under `/interactive-input/api/v1/`, permission‑checked, CSRF‑protected, with a stable envelope.
- 🌉 **`inputStepBridge`** — opt‑in reconciliation that mirrors *existing* native `input` steps into the bell/modal/API, forwarding answers back to the native step. Zero pipeline changes.
- ⏱️ **SLA + retention** — expire overdue questions; compact terminal ones after a retention window.
- ⚙️ **JCasC‑native** — configure everything as code; every capability is a feature flag.
- 🔒 **Secure by construction** — server‑side Markdown escaping, permission checks at every endpoint, no existence leaks, admin‑gated global list.

---

## How it works

```mermaid
flowchart LR
  subgraph Pipeline
    A["askInteractive(...)"] -->|register| S[(QuestionStore\nXmlFile-persisted)]
    B["native input(...)"] -.->|opt-in bridge| S
  end
  S --> BELL["🔔 Notification bell\n(polls REST)"]
  S --> REST["/interactive-input/api/v1/**"]
  BELL --> MODAL["Rich modal"]
  MODAL -->|answer/abort| REST
  AGENT["External agent\n(any language)"] -->|answer/abort| REST
  REST -->|resolve| S
  S -->|resume/throw/timeout| A
  S -.->|forward proceed/abort| B
  TICK["SLA ticker\n(AsyncPeriodicWork)"] --> S
```

A paused step registers a `Question` in a durable, permission‑aware `QuestionStore`. The bell polls the REST API for questions the current user may answer; the modal (or any external agent) answers via `POST …/answer`; the store resolves the question and the pipeline resumes, throws (`abort`), or times out (SLA). Question metadata survives a controller restart via XStream; transient resolvers are re‑attached on step resume.

---

## Install

**Requirements:** Jenkins **2.555.2+**, Java **17+**, and `pipeline-input-step` **≥ 560** (see [compatibility](#compatibility-matrix)).

### From the built HPI

1. **Manage Jenkins → Plugins → Advanced → Deploy Plugin** and upload `interactive-input.hpi`, **or** drop the HPI into `$JENKINS_HOME/plugins/` and restart.
2. Confirm the bell appears and the health probe answers:

```bash
curl -s http://<jenkins>/interactive-input/api/v1/health
# {"status":"ok","pending":0}
```

### From the Update Center

Once published to [plugins.jenkins.io](https://plugins.jenkins.io/), search **"Interactive Input"** in **Manage Jenkins → Plugins → Available**.

---

## Quick start

```groovy
pipeline {
  agent any
  stages {
    stage('Approve deploy') {
      steps {
        script {
          def answer = askInteractive(
            prompt: 'Deploy build to production?',
            choices: [
              [id: 'approve', label: 'Approve', why: 'Release notes look good; all checks green.'],
              [id: 'reject',  label: 'Reject',  why: 'Needs another round of testing.']
            ],
            allowFreeText: true,
            contextMarkdown: '''## Release 4.2.0
- Fixes CVE‑2026‑1234
- Adds retry to the payment worker''',
            slaMinutes: 30
          )
          echo "Human chose: ${answer}"
        }
      }
    }
  }
}
```

When this build reaches the step it pauses, the bell lights up for everyone allowed to answer, and the pipeline resumes the moment a human (or an authorised agent) responds.

---

## The `askInteractive` step

| Parameter | Type | Default | Description |
|---|---|---|---|
| `prompt` | String (required) | — | The question shown as the modal title. Must not be blank. |
| `choices` | List of maps | `[]` | Each: `[id: 'x', label: 'Label', why: 'optional rationale']`. |
| `allowFreeText` | boolean | `false` | Allow a Markdown free‑text answer with live preview. |
| `slaMinutes` | int | `-1` → global default | Auto‑expire after N minutes. `0` = wait forever. |
| `submitterFilter` | String | `null` | Comma‑separated users/groups permitted to answer (same semantics as `input`'s `submitter`). |
| `contextMarkdown` | String | `null` | Rich context rendered (safely) in the modal. |
| `escalation` | String | `null` | Reserved for v0.2 (Slack/email/PagerDuty). Accepted but ignored in v0.1. |

**Return value**

- A **choice** was picked → the choice `id` (`String`).
- **Free text** was submitted → `[text: '…', choice: null]`.
- **Abort / Deny** → throws `AbortException` (fails the stage unless you `catch` it).
- **SLA expiry** → throws a timeout, so a paused build cannot idle forever.

---

## Human-in-the-loop scenarios

Every pause shows the **same rich modal**. What changes is the *shape* of the question, and that is set by two `askInteractive` inputs: `choices` (zero or more options to pick from) and `allowFreeText` (whether a typed answer is allowed). If one build asks several questions at once, they become the numbered **"series" slider**.

Each modal shows the question, a `<job> #<build> · started by <user>` line, and three buttons: **Answer** (send the picked option or typed text), **Deny** (reject — the pipeline's `askInteractive` throws `AbortException`, so the step fails), and **Cancel** (just close the dialog).

### From an AI agent

An autonomous agent (a bot, a script, or an LLM copilot) pauses mid-task and asks a human through the plugin. The six shapes below cover the human loops agents hit in practice. The screenshots are live captures from a demo pipeline where a Cursor-SDK agent drives each shape.

**1. Approve / Deny** — a two-button gate. The agent proposes an action; the human approves or rejects it.

![Approve / Deny modal](docs/screenshots/scenarios/Scenario_approve_deny.png)

**2. Single option** — a one-button acknowledgement (e.g. *"Maintenance window starts now. Acknowledge to continue."*). Used when the agent needs a human to confirm they have seen something before it proceeds.

![Single-option acknowledgement modal](docs/screenshots/scenarios/Scenario_single_option.png)

**3. Multiple choice** — pick exactly one of N options, no free text. Here the agent asks which environment to deploy to.

![Multiple-choice modal](docs/screenshots/scenarios/Scenario_multiple_choice.png)

**4. Multiple choice + user input** — pick a listed option **or** type your own. Radio choices plus a Markdown-aware text box with live preview.

![Multiple-choice-plus-user-input modal](docs/screenshots/scenarios/Scenario_multiple_choice_plus_user_input.png)

**5. Free text** — no choices, just a typed answer (Markdown supported, with preview). Used for free-form values such as a change-ticket id or a release note.

![Free-text modal](docs/screenshots/scenarios/Scenario_free_text.png)

**6. Series (sliding modal)** — several questions published on the same build at once. The modal shows a numbered pager (`‹ Prev · 1 / 3 · Next ›` plus clickable pips); answering advances to the next slide, and in-progress typing is preserved as you page back and forth.

![Series sliding modal](docs/screenshots/scenarios/Scenario_series_sliding_modal.png)

### Without an AI agent (human- or CI-driven)

The same surface is just as useful with **no AI in the loop** — the modal is identical, only *who answers* differs, so these need no separate screenshots:

- **Manual deploy approval** — a `Jenkinsfile` calls `askInteractive` with Approve/Reject choices; a release manager clicks **Approve** in the bell or the job-page box. The classic change gate, now with an in-UI signal instead of a silent pause.
- **Choice-driven configuration** — pick one of several environments / targets / release tags; the returned `id` drives the rest of the pipeline (`if (answer == 'prod') { … }`).
- **Free-text capture for the record** — collect a change-ticket id or a deploy note and attach it to the build as an audit trail — no agent required.
- **Existing `input` steps, lit up** — turn on `inputStepBridge` and every *native* `input` in your current pipelines gains the bell / badge / modal with **zero pipeline edits** (see [Bridging existing `input` steps](#bridging-existing-input-steps)).
- **Answered by another system** — a non-AI script, a ChatOps bot, or an upstream CI job answers via the [REST API](#rest-api) (`POST …/answer`) instead of a human clicking — the same permission checks apply.
- **Time-boxed approval** — set `slaMinutes` so an unattended gate auto-expires (throws) instead of pausing forever.

---

## Drive it from an AI agent (Python)

The idea is simple: your AI agent is doing some work, it reaches a point where a **person** must decide, so it **stops and asks a human** — and the plugin shows that question in Jenkins. The agent waits, the human clicks an answer, and the agent carries on with that answer.

The agent asks by calling a **custom tool** (explained just below). **Cursor SDK is used here as one example only** — the same pattern works with **any** AI agent app or framework, in **any** programming language.

> The snippets below are the essential wiring: an agent that exposes an `ask_human` (and `ask_human_series`) tool, and a pipeline that turns each tool call into an `askInteractive(...)` step. Set `CURSOR_API_KEY`, then run one stage per shape.

### What is a "custom tool", and how should it look?

A **custom tool** (some frameworks call it a *function tool*, a *function call*, or *tool use*) is just a function you register with your agent. You give it a **name**, a short **description**, and the **inputs** it accepts; the model then calls it by name — passing JSON arguments — whenever it decides it needs that capability. Here, the tool's job is *"ask a human, and wait for the answer."*

For this plugin, a good `ask_human` tool has four parts:

1. **Name** — something the model will understand, e.g. `ask_human` (plus `ask_human_series` for a batch of questions).
2. **Description** — tells the model *when* to use it, e.g. *"Ask the human one question and block until they answer."*
3. **Inputs (schema)** — `prompt` (the question text, required), optional `choices` (a list of `{id, label}` options to pick from), and optional `allow_free_text` (allow a typed answer). These three inputs are what choose the modal shape (see [the scenarios above](#human-in-the-loop-scenarios)).
4. **What it does when called (`execute`)** — it must:
   - **send** the question to Jenkins — call the plugin's [`POST` REST API](#rest-api), or use a small file-queue bridge;
   - **wait (block)** until a human answers in the modal — this is the important part: the agent should *pause here*, not continue;
   - **return the answer** as a string (the chosen `id`, or the typed text) so the model can act on it.

That is the whole contract. Everything else is just which `choices` / `allow_free_text` you pass.

### Works with any agent framework (and any language)

The plugin never talks to a model itself — it only speaks **HTTP + JSON**. So *anything* that can make an HTTP request can answer a question, and you can wire the `ask_human` tool into whatever you already use, for example:

- **Cursor SDK** (used in the sample below), **OpenAI** (function calling / Assistants), **Anthropic Claude** (tool use), **Google Gemini / ADK** (function calling), **LangChain / LangGraph**, **LlamaIndex**, **CrewAI**, **Microsoft AutoGen**, **Semantic Kernel**, or the **Vercel AI SDK**.
- Or **no framework at all** — a plain script that `POST`s to the REST API, or an **MCP** server that exposes the same "ask a human" tool.

Because it is just HTTP, the programming language is your choice: **Python, JavaScript / TypeScript (Node), Java / Kotlin, Go, Rust, C# / .NET, Ruby, PHP, or Bash + `curl`** all work equally well. The example below happens to use **Python + Cursor SDK**.

### One-time wiring (Cursor SDK example)

```python
import os
from cursor_sdk import Agent, CustomTool, CustomToolContext, LocalAgentOptions

def ask_human(args: dict, ctx: CustomToolContext) -> str:
    # Hand the question to Jenkins (via the plugin's REST API, or a small
    # file-queue bridge) and block until a human answers in the modal.
    # Returns the chosen choice id, or the typed free text.
    return publish_to_jenkins_and_wait(args)      # your impl: POST to the REST API, then wait

tools = {
    "ask_human": CustomTool(
        description=(
            "Ask the human ONE question and block until they answer. Pass 'prompt', "
            "optional 'choices' (list of {id,label}), and optional 'allow_free_text'. "
            "Returns the chosen id, or the typed text."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "choices": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {"id": {"type": "string"}, "label": {"type": "string"}},
                        "required": ["id", "label"],
                    },
                },
                "allow_free_text": {"type": "boolean"},
            },
            "required": ["prompt"],
        },
        execute=ask_human,
    ),
}

with Agent.create(
    model="sonnet",
    api_key=os.environ["CURSOR_API_KEY"],
    local=LocalAgentOptions(cwd=".", custom_tools=tools),
) as agent:
    agent.send("You are a deploy agent. When you need a human decision, call ask_human.")
```

### One snippet per scenario

Only `choices` and `allow_free_text` change between shapes — the plugin renders the matching modal. Each block is the argument object the model passes to the tool:

```python
# 1) Approve / Deny  ── tool: ask_human  →  returns "approve" or "deny"
{"prompt": "Approve deploy of build to PRODUCTION?",
 "choices": [{"id": "approve", "label": "Approve"},
             {"id": "deny", "label": "Deny"}],
 "allow_free_text": False}

# 2) Single option  ── tool: ask_human  →  returns "ack"
{"prompt": "Maintenance window starts now. Acknowledge to continue.",
 "choices": [{"id": "ack", "label": "Acknowledge"}],
 "allow_free_text": False}

# 3) Multiple choice  ── tool: ask_human  →  returns "dev" | "staging" | "prod"
{"prompt": "Which environment should I deploy to?",
 "choices": [{"id": "dev", "label": "Dev"},
             {"id": "staging", "label": "Staging"},
             {"id": "prod", "label": "Production"}],
 "allow_free_text": False}

# 4) Multiple choice + user input  ── tool: ask_human  →  a listed id OR typed text
{"prompt": "Pick a release tag, or type your own:",
 "choices": [{"id": "latest", "label": "latest"},
             {"id": "stable", "label": "stable"}],
 "allow_free_text": True}

# 5) Free text  ── tool: ask_human  →  the typed change-ticket id
{"prompt": "Enter the change ticket id to attach to this deploy:",
 "choices": [],
 "allow_free_text": True}

# 6) Series (sliding modal)  ── tool: ask_human_series  →  JSON array of {prompt, answer}
{"questions": [
    {"prompt": "Which environment?",
     "choices": [{"id": "staging", "label": "Staging"},
                 {"id": "prod", "label": "Production"}]},
    {"prompt": "Run database migrations?",
     "choices": [{"id": "yes", "label": "Yes"}, {"id": "no", "label": "No"}]},
    {"prompt": "Deploy note (free text):", "allow_free_text": True}]}
```

Publishing all the series questions at once is what makes several questions wait on the same build at the same time — and that is what the plugin shows as the numbered sliding modal (shape 6 above).

---

## REST API

Base path: `/interactive-input/api/v1/`. All responses are JSON. Mutating endpoints require `POST` **and** a Jenkins CSRF crumb.

| Method | Path | Permission | Purpose |
|---|---|---|---|
| `GET` | `/health` | anonymous | Liveness probe: `{"status":"ok","pending":N}`. |
| `GET` | `/questions` | Overall/Read | Questions **you** can answer. `?job=<fullName>` ⇒ that job's answerable questions (per‑project centre; Item/Read, 404 otherwise). `?job=<fullName>&build=<n>` ⇒ that build's questions incl. settled ones and the recorded answer (audit). `?all=true` ⇒ every waiting question (**Overall/Administer**). |
| `GET` | `/questions/{id}` | Item/Read on source job | Full detail incl. sanitised `contextHtml` and `startedBy` (404 if missing *or* unreadable — no existence leak). |
| `POST` | `/questions/{id}/answer` | Item/Build (or submitter) | Submit `{"choiceId":"…"}` or `{"freeText":"…"}`. |
| `POST` | `/questions/{id}/abort` | Item/Build (or submitter) | Cancel the input (delivers an abort to the pipeline). |
| `POST` | `/preview` | Overall/Read | Render Markdown → safe HTML (used by the modal's free‑text preview). |

### Worked example (`curl`)

```bash
BASE=http://<jenkins>
# 1) CSRF crumb (session-bound — keep the cookie jar)
CRUMB=$(curl -s -c cj.txt -u "$USER:$TOKEN" \
  "$BASE/crumbIssuer/api/json" \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['crumbRequestField']+':'+d['crumb'])")

# 2) List questions I can answer
curl -s -b cj.txt -u "$USER:$TOKEN" "$BASE/interactive-input/api/v1/questions"
# {"count":1,"questions":[{"id":"…","prompt":"Deploy build to production?","choices":[…],"allowFreeText":true,"jobFullName":"deploy","buildNumber":42,"status":"WAITING","remainingMs":1740000, …}]}

# 3) Answer with a choice
curl -s -b cj.txt -u "$USER:$TOKEN" -H "$CRUMB" -H 'Content-Type: application/json' \
  --data '{"choiceId":"approve"}' \
  "$BASE/interactive-input/api/v1/questions/<id>/answer"
# {"id":"…","status":"ANSWERED","answer":{"choiceId":"approve","answeredBy":"darnr", …}}
```

**Answer envelope** — `choiceId` must match a declared choice (or the `__deny__` sentinel); `freeText` is only accepted when the question set `allowFreeText: true`. Invalid answers → `400`; unauthorised → `403`; already‑settled → `409`.

---

## Bridging existing `input` steps

Turn on **`inputStepBridge`** and every *pending native `input`* is mirrored into the bell, modal, and REST API — with **no pipeline changes**:

- Parameter‑less inputs get a single **Approve / Proceed** choice; answering it (via modal or REST) forwards to the native step's `proceed`, honouring any `submitterParameter`.
- **Deny** forwards to the native `abort`.
- Parameterised inputs are surfaced read‑only with a deep link to the build's input form (full in‑modal parameter answering is a v0.2 item).
- If a user answers via the built‑in UI instead, the next reconciliation drops the now‑settled mirror.

This is the fastest way to get notifications for pipelines you don't want to rewrite.

### Stage View / Pipeline Graph View "input required" cell

The **Pipeline Stage View** and **Pipeline Graph View** render their built‑in "paused for input" prompt off the native `input` step's `InputAction`. Because the bridge mirrors **real** native `input` steps (rather than replacing them), that indicator keeps working exactly as before — and the same pause now *also* surfaces in the bell, the job‑page box and the build‑history badge. So the recommended way to get an "input needed" marker **in the stage/graph view** is:

- Use a native `input` step with **`inputStepBridge` on** → the stage/graph view shows the standard input‑required cell *and* our surfaces mirror it.
- Use **`askInteractive`** when you want the richer surface (Markdown context, per‑choice rationale, SLA, REST answering) → it advertises the pause through the job‑page box, the build‑history badge (both pulsing), the sidebar page, the bell, and the anchored console link. `askInteractive` does not draw the native stage‑view cell, because that cell is owned by the core `input`/stage‑view plumbing.

---

## Settings and screens

A visual tour of where to configure the plugin and what it looks like in use. (The [Configuration](#configuration-ui--jcasc) section below is the equivalent **as-code / JCasC** reference.)

### Appearance settings

**Where:** *Manage Jenkins → Appearance → Interactive Input.* This is the home for the notification surfaces' look-and-feel (kept out of functional config, per Jenkins core guidance).

![Appearance settings for Interactive Input](docs/screenshots/settings_at_appearance.png)

- **Global notification centre (header bell)** — turns on the header bell. On the **dashboard** it lists **every** question you can answer; **inside a pipeline** (a job/build page) it narrows to **that pipeline's** questions. *Off by default*, so notifications surface per pipeline / per build rather than at one Jenkins-wide point.
- **Per-project notification centre** — the per-pipeline / per-build surfaces: a sidebar page on each job, an "awaiting input" badge next to the waiting build in the build-history list, and the per-build audit view. *On by default.*
- **Show the inline box on the job page** — the large "Interactive Input" box on a job/pipeline page while it has a pending question. Turn it off to keep the badge + sidebar page **without** the big box. *On by default* (requires the per-project centre above).
- **Show each user only their own build's notifications** — when on, every surface shows a question **only to the user who started the owning build**. Builds started by SCM, a timer, an upstream job, or the system have no human owner, so they stay visible to everyone. *Off by default* (everyone who may answer sees it).
- **Only the build starter may answer (others can view)** — when on, only the build's starter (or a Jenkins administrator) can **submit** an answer; everyone else sees the question read-only with the controls locked. This is an *extra* restriction layered on top of the usual Job/Build + submitter checks — it never grants access. *Off by default.*
- **Notification icon** — the icon used across the bell, badge, and sidebar link, chosen from eight meaning-matched Ionicons (speech bubble *(default)*, raised hand, person, pull-request, megaphone, hourglass, alert, classic bell). The capture above is set to **Raised hand — human action needed**.

### Per-pipeline notifications *(preview — not yet delivered)*

**Where:** *&lt;your pipeline&gt; → Configure → Interactive Input notifications.* Each pipeline can declare **where** its interactive-input notifications should be pushed.

![Per-pipeline notification settings](docs/screenshots/settings_at_pipeline_for_push_notification.png)

- Toggles for **Notify by email** and **Notify Microsoft Teams**, a **Recipients** field (comma-separated addresses / channel handles), and an optional **Webhook credentials ID** for a Teams/webhook integration.
- **Status:** these preferences are **persisted only** — outbound delivery (email / Microsoft Teams / webhooks) ships in a future release, as the form states inline. Filling it in now is safe and forward-compatible; nothing is sent yet.

### The Interactive Input page

**Where:** open any build → **Interactive Input** in the left sidebar (also reachable from the anchored link the step writes into the build **Console Output**).

![The per-build Interactive Input audit page](docs/screenshots/interactive_input_page.png)

This is the **per-build audit view** — the compliance trail for every human-in-the-loop question that build raised. Each row shows the **prompt**, a status badge (**ANSWERED** / waiting / aborted / expired), **who started** the build, and — once settled — **who answered, what they chose (or typed), and when**. It records both `askInteractive` questions and any native `input` steps surfaced by the bridge, so *"what was asked and what was decided"* stays answerable long after the build finishes.

---

## Configuration (UI + JCasC)

Settings are split in two, following Jenkins core guidance to keep look‑and‑feel out of functional config:

- **Functional flags** live under **Manage Jenkins → System → Interactive Input** (`unclassified.interactiveInput`).
- **Notification‑surface visibility** (the global bell + its scoping, the per‑project centre, the job‑page box, and the icon) lives under **Manage Jenkins → Appearance → Interactive Input** (`appearance.interactiveInputAppearance`).

```yaml
unclassified:
  interactiveInput:
    features:
      askInteractiveStep: true   # the askInteractive step
      richModal: true            # rich modal (else deep-link to the build)
      restApi: true              # /interactive-input/api/v1/**
      inputStepBridge: false     # surface existing native input steps (opt-in)
      dashboardTile: false       # reserved for v0.2
    polling:
      intervalSeconds: 15        # poll cadence for the bell and per-project widgets (min 5)
    sla:
      defaultMinutes: 0          # default SLA when a step omits slaMinutes (0 = no SLA)
    retentionDays: 7             # keep answered/aborted/expired questions this long

# Look-and-feel — Manage Jenkins → Appearance → Interactive Input
appearance:
  interactiveInputAppearance:
    notificationCentre: false          # global header bell (off by default). On dashboard = all
                                       # answerable questions; inside a pipeline = only that pipeline's.
    perProjectCentre: true             # per-project surfaces (sidebar page, build badge, audit view)
    jobPageBox: true                   # the large inline box on the job page (independent of the badge)
    icon: "chatbubble-ellipses"        # one of: chatbubble-ellipses, hand-left, person-circle,
                                       # git-pull-request, megaphone, hourglass, alert-circle, notifications
```

Defaults: the step, **per‑project notification centre**, the **job‑page box**, the modal, and the REST
API are **on**; the global bell (`notificationCentre`), the bridge, and the dashboard tile are **off**.
Per‑pipeline notification preferences live on each pipeline's **Configure** page (saved now; delivery later).

---

## Security model

- **Permissions mirror `pipeline-input-step`.** Answering/aborting requires `Item/Build` on the source job, or — when a `submitterFilter` is set — membership in that user/group set (with the usual `Overall/Administer` bypass). Viewing requires `Item/Read`.
- **CSRF everywhere it mutates.** Every `answer`/`abort`/`preview` is `@RequirePOST`, so Jenkins' crumb filter applies.
- **No existence leak.** `GET /questions/{id}` returns `404` whether the question is missing *or* you lack `Item/Read`.
- **Admin‑gated global view.** `?all=true` requires `Overall/Administer`; the default list is scoped to what you can answer.
- **Safe Markdown.** `contextMarkdown` and free‑text are rendered with commonmark configured to **escape raw HTML** and **sanitise URLs** (`javascript:` and friends are stripped). The client inserts only server‑sanitised HTML via `innerHTML`; all other user data goes through `textContent`.
- **Only `/health` is anonymous** (a liveness probe that leaks nothing but a pending count).

See [`docs/SECURITY.md`](docs/SECURITY.md) for the threat model and how to report issues.

---

## Language applicability & restrictions

**Which programming languages are supported?** Two different things are involved, so it helps to split them:

- ✅ **Answering a question — any language.** The bell, modal, REST API, and bridge only speak **HTTP + JSON**. So the program that answers (your app under test, your deploy tool, your AI agent) can be written in **any** language that has an HTTP client: **Python, JavaScript / TypeScript (Node), Java / Kotlin, Go, Rust, C# / .NET, Ruby, PHP, or Bash + `curl`**. This is what makes `interactive-input` a general "wait for a human" point, not a Groovy‑only feature.
- ⚠️ **Declaring the pause — Jenkins Pipeline (Groovy).** Like every Jenkins step, `askInteractive` is called from a `Jenkinsfile` (Groovy). You do **not** rewrite your app in Groovy — your program, in any language, takes part by (a) being run by that pipeline and/or (b) answering through the REST API. The pipeline is only the place where the pause is declared.
- ➡️ **Already have native `input` steps in other pipelines?** Turn on `inputStepBridge` and they show up in the bell with **no code changes**.

**Restrictions (v0.1):**

| # | Restriction | Why |
|---|---|---|
| 1 | Jenkins **2.555.2+**, Java **17+** | Built against the 2.555.x BOM on Java 17 bytecode. |
| 2 | `pipeline-input-step` **≥ 560** | The bridge/modal target the dialog surface added in 560. |
| 3 | `askInteractive` runs in **Pipeline** jobs (not Freestyle) | It's a pipeline step; Freestyle has no step model. Freestyle/other jobs can still use the **REST API**. |
| 4 | Notifications are **polled**, not pushed | No SSE/WebSocket in v0.1 (proxy‑friendly by design). Cadence ≥ 5s. |
| 5 | Bridge answers **parameter‑less** native inputs in‑modal | Parameterised native inputs deep‑link to the build form (v0.2). |
| 6 | Escalation (Slack/email/PagerDuty) is **accepted but ignored** | Reserved for v0.2. |

---

## Compatibility matrix

| Component | Version | Notes |
|---|---|---|
| Jenkins core | `2.555.2+` | pinned via `bom-2.555.x` |
| Java | `17` (built on JDK 21) | `maven.compiler.release=17` |
| `pipeline-input-step` | `≥ 560.v56198a_642157` | **hard requirement** (modal + bridge) |
| `configuration-as-code` | optional | JCasC is optional at runtime |
| `commonmark` | `0.24.0` | bundled in the HPI for safe Markdown |

Full dependency inventory: [`docs/BILL_OF_MATERIALS.md`](docs/BILL_OF_MATERIALS.md).

---

## Build from source

```bash
# Requires JDK 17+ and Maven 3.8.6+
mvn -B -ntp clean verify      # runs the full test suite + SpotBugs
ls target/interactive-input.hpi
```

Behind a corporate proxy, configure `~/.m2/settings.xml` and point Maven at `https://repo.jenkins-ci.org/public/`.

---

## Project docs

| Doc | What's in it |
|---|---|
| [`CHANGELOG.md`](CHANGELOG.md) | Release history (Keep a Changelog). |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Dev setup, coding standards, PR flow. |
| [`docs/BILL_OF_MATERIALS.md`](docs/BILL_OF_MATERIALS.md) | Full dependency + build BOM with versions and licenses. |
| [`docs/LICENSING.md`](docs/LICENSING.md) | Why MIT, and a primer on OSS license families. |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Threat model + responsible disclosure. |

---

## License

Released under the [MIT License](LICENSE) © 2026 Darniss `<darniss.mail@gmail.com>`.

> Maintainer: **Darniss** — `darniss.mail@gmail.com`

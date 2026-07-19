# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

_Nothing yet._

## [0.1.0] - 2026-07-19

Initial release.

### Added
- **`askInteractive` pipeline step** — a durable human-in-the-loop input step that surfaces in the
  notification bell and rich modal. Returns the chosen id (or free text), throws on abort, and times
  out on SLA. Parameters: `prompt`, `choices` (`[id, label, why]`), `allowFreeText`, `slaMinutes`,
  `submitterFilter`, `contextMarkdown` (`escalation` reserved for v0.2).
- **Notification bell** (`PageDecorator`) with an unread count scoped to what the current user may
  answer; proxy-friendly polling (no SSE/WebSocket), cadence configurable with a 5s floor.
- **Rich modal** (vanilla JS) — Markdown context panel, radio choices with per-choice rationale,
  optional free-text with a live server-sanitised preview, and full keyboard/focus-trap accessibility.
- **Versioned REST API** under `/interactive-input/api/v1/`: `health`, `questions`, `questions/{id}`,
  `questions/{id}/answer`, `questions/{id}/abort`, and `preview`. JSON envelope, permission-checked,
  CSRF-protected.
- **`inputStepBridge`** (opt-in) — mirrors pending native `input` steps into the bell/modal/API and
  forwards answers/aborts back to the native step (honours `submitterParameter`); drops stale mirrors.
- **Durable `QuestionStore`** — XStream-persisted question registry with concurrency-safe transitions
  and transient resolvers re-attached on step resume (survives controller restart).
- **SLA + retention** via an `AsyncPeriodicWork` ticker: expire overdue questions; compact terminal
  ones after `retentionDays`.
- **JCasC support** — full configuration under `unclassified.interactiveInput`
  (`features`, `polling`, `sla`, `retentionDays`); every capability is a feature flag.
- **Safe Markdown rendering** — commonmark configured to escape raw HTML and sanitise URLs.
- **Test suite** — 39 JUnit 5 tests across model, markdown, step, JCasC round-trip, permissions/SLA,
  REST, and the input bridge.

### Security
- All mutating endpoints are `@RequirePOST` (CSRF-protected) and enforce `pipeline-input-step`-parity
  permissions; `GET /questions/{id}` avoids existence leaks; `?all=true` is admin-gated.

### Compatibility
- Requires Jenkins **2.555.2+**, Java **17+**, and `pipeline-input-step` **≥ 560**.
- `commonmark` **0.24.0** is bundled; `configuration-as-code` is an optional runtime dependency.

[Unreleased]: https://github.com/jenkinsci/interactive-input-plugin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/jenkinsci/interactive-input-plugin/releases/tag/v0.1.0

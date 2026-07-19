# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Per-project notification centre** — notifications now surface per pipeline/build instead of only
  at one Jenkins-wide point:
  - `InteractiveInputJobAction` (`TransientActionFactory<Job>`) — an inline box on the job/pipeline
    page (`jobMain.jelly`) plus a sidebar page (`index.jelly`) listing that job's pending questions;
    the link/box appear only when something is pending.
  - `InteractiveInputRunAction` (`BuildBadgeAction`, `TransientActionFactory<Run>`) — an "awaiting
    input" badge in the build-history list while a build waits, and a per-build **audit view** showing
    what was displayed and what was chosen. Attached only to builds that used interactive-input.
  - REST scoping: `GET /questions?job=<fullName>` (per-project, `Item.READ`, 404 no-leak) and
    `?job=<fullName>&build=<n>` (per-build audit incl. the recorded answer).
- **Attribution** — `Question.startedBy` (resolved via `CauseResolver`: user id, else
  `scm`/`timer`/`upstream`/`system`) is populated by the step and the bridge, exposed in the REST JSON,
  and rendered as "started by &lt;user&gt;" on every surface.
- **Anchored console audit link** — the `askInteractive` step logs a `HyperlinkNote` to the per-build
  audit view at invocation (like the built-in `input`), marks the flow node **Paused**
  (`PauseAction`), and logs the resolved outcome (answered/aborted/expired, by whom, and what was
  chosen).
- **Per-pipeline notification preferences** — `InteractiveInputJobProperty` (`OptionalJobProperty`)
  adds an *Interactive Input notifications* section to a pipeline's **Configure** page (email/Teams/
  recipients/webhook). Preferences are **persisted only**; delivery ships in a future release.
- **Appearance configuration** — new `InteractiveInputAppearanceConfig` (`GlobalConfiguration` in the
  `AppearanceCategory`) surfaces under **Manage Jenkins → Appearance → Interactive Input**, and as code
  under `appearance.interactiveInputAppearance`. It holds three independent on/off switches and an icon
  chooser:
  - `notificationCentre` (default **off**) — the global header bell, now **context-scoped**: the
    dashboard lists every answerable question; inside a pipeline (job/build page) it narrows to that
    pipeline's questions (server-side `NotificationBell` resolves the `Job` ancestor and the client
    calls `GET /questions?job=<fullName>`).
  - `perProjectCentre` (default **on**) — gates the per-project surfaces (migrated here from `features`).
  - `jobPageBox` (default **on**) — independently toggles the large inline box on the job page, so the
    badge + sidebar can be kept without the box.
  - `icon` (default `chatbubble-ellipses`) — the notification icon used across the bell, badge and
    sidebar, chosen from eight meaning-matched Ionicons (`ionicons-api`).
- **Attention pulse** — the build-history "awaiting input" badge and the job-page box title blink
  slowly in red (`@keyframes ii-attn-pulse`), with a `prefers-reduced-motion` fallback.
- **Functional configuration UI** — the feature flags (including the opt-in `inputStepBridge`) are now
  toggleable under **Manage Jenkins → System → Interactive Input** (`InteractiveInputGlobalConfig`
  `config.jelly` + `Features/config.jelly`), not only via JCasC. `configure()` starts the flags all-off
  before binding (so an unchecked box turns the flag off) and leaves polling / SLA / retention — which
  are not on this form — untouched.
- **User-scoped notifications & lock** — two more Appearance switches (also as code under
  `appearance.interactiveInputAppearance`), layered on top of the existing permission checks:
  - `userScopedNotifications` (default **off**) — when on, every surface (bell, per-project box,
    build-list badge, sidebar) shows a viewer only the questions for **builds they started**, plus
    ownerless builds (trigger/SCM/timer-started, which have no human owner). When off, behaviour is
    unchanged (everyone who can answer sees everything).
  - `lockToBuildStarter` (default **off**) — when on, non-starters may **see** others' questions but
    cannot answer them (`Jenkins.ADMINISTER` still overrides). Enforced server-side in the store and
    REST answer/abort; the REST JSON now carries a per-question `canAnswer` flag and the modal shows a
    "Locked." note with disabled buttons when it is `false`.
  - Store surface: `QuestionStore.listNotifications*/countNotifications*/hasNotificationForBuild` and
    `canAnswerEffective`; ownership resolved via `CauseResolver.isRealUser`.
- **Multi-question "series" modal** — when two or more questions are waiting for the same scope, the
  bell dropdown and job-page box offer **"Answer all (N)"**, opening one modal that pages through the
  questions with a numbered slider (Prev/Next + clickable pips, answered slides marked done). The
  build-list dot opens the series directly when its build has more than one waiting question.

### Changed
- **Build-list badge is now an empty red pulsing dot that opens the answer modal in place.** It no
  longer renders the notification icon and no longer navigates to the per-build audit page; clicking it
  opens the same modal the header bell uses, on the current page. Handled by `bell.js` via
  `[data-ii-badge]` event delegation, so it also works for build rows the async build-history widget
  injects after the script runs; the `href` to the audit page remains a no-JS fallback.
- **Notification-surface settings moved from `features` to Appearance.** `navBarBell` (now
  `notificationCentre`) and `perProjectCentre` are no longer functional feature flags; they live under
  **Appearance** per Jenkins core guidance to separate look-and-feel from functional config. The
  functional `features` block keeps `askInteractiveStep`, `richModal`, `restApi`, `inputStepBridge`,
  `dashboardTile`.
- The global bell, when enabled, is anchored into the header controls (with a bottom-right floating
  fallback) so it no longer overlaps the settings gear.
- The rich modal's **context panel is expanded by default**.
- Notification icons now render via `ionicons-api` `<l:icon>` (theme-aware) instead of a hardcoded SVG.
- `bell.js` refactored into a shared client (helpers + modal + answer/preview) reused by the bell and
  the per-project job/audit widgets; the bell clones the operator-chosen icon and scopes its query to
  the current pipeline.
- **The left-sidebar "Interactive Input (N)" count is now live.** `jobMain.jelly` always renders a
  hidden `[data-ii-tasklink]` controller (even at zero) that `bell.js` uses to poll the scoped endpoint
  and re-label the sidebar row — and hide it at zero / re-show it when work arrives — so the number no
  longer stays stale until a full page reload.

### Fixed
- **Notification bell now appears inside a pipeline/job, not only on the dashboard.** The shared
  `bell.js` adjunct is emitted by `jobMain.jelly` in the job page's *main panel* — earlier in the
  document than the footer bell mount (`#interactive-input-bell`, a `PageDecorator`) and the sidebar
  `[data-ii-tasklink]` controller. The script collected its mounts at top-level execution, so on a job
  page those elements did not exist yet and neither the bell nor the live sidebar controller mounted
  (on the dashboard there is no `jobMain.jelly`, so it worked). Mount discovery + bootstrap now run on
  `DOMContentLoaded`, so every surface mounts regardless of where the adjunct is emitted.
- **Left-sidebar "Interactive Input (N)" count updates live.** Two causes: the poller
  (`mountTaskLink`) never ran on job pages (same bootstrap-timing bug above), and its href match was
  exact while core renders the link *without* a trailing slash (`…/interactive-input`) — so even when
  it ran it failed to find the existing link and cloned a duplicate. The match is now
  slash-insensitive (`normPath`), and the count re-labels / the row hides live on answer (via the poll
  and the `ii:answered` event) without a page reload.
- **Series ("Answer all") modal no longer loses a half-typed answer.** Paging between questions
  re-fetched each one and rebuilt the form empty, discarding unsubmitted free text / the selected
  choice. The pager now snapshots a per-question **draft** before navigating and restores it, rendering
  each slide from the already-fetched list item instead of re-fetching.
- **Stale ("dummy") bridged notifications now clear promptly.** When a native `input` (surfaced by
  `inputStepBridge`) was answered through the built-in console/stage-view UI, our mirror stayed WAITING
  — showing a stale entry in the bell and a stale build-list badge — until the next 30s ticker
  reconciliation, which was the only cleanup path. `GET /questions?job=<name>` now calls a **scoped
  bridge reconcile** (`InputStepBridge#reconcile(String)`) before listing, so a pipeline's surfaces
  self-heal within one poll (≤15s) without disturbing other jobs. The 30s ticker still reconciles
  every job (`sync()` = `reconcile(null)`).
- **Answers refresh every surface immediately.** Answering in any modal dispatches an `ii:answered`
  DOM event; the bell, per-project box and build-list badge listen for it and refresh at once instead
  of waiting for their next poll (the answered build's badge is removed once nothing is left waiting).
- **Build-history badge blinks everywhere.** The "awaiting input" badge (`badge.jelly`) pulls in the
  shared style adjunct itself, so the slow red attention pulse renders in the build list even when both
  the header bell (`notificationCentre`) and the job-page box (`jobPageBox`) are off — previously the
  badge relied on one of those surfaces to have loaded `bell.css`. Adjunct includes are idempotent, so
  no double-load. This applies to every waiting build, including native `input` builds surfaced by
  `inputStepBridge`.

### Notes / trade-offs
- The durable audit record is the build console line; the per-build audit *page* is a live view of the
  store and shows an empty state after retention compaction.
- **Stage View / Pipeline Graph View "input required" cell** is owned by the core `input`/stage-view
  plumbing (keyed off `InputAction`). With `inputStepBridge` on, native `input` steps keep that cell
  *and* mirror into our surfaces. `askInteractive` advertises its pause through our own surfaces (box,
  pulsing badge, sidebar, bell, anchored console link) rather than drawing the native cell.

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

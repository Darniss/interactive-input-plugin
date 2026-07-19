# Session Notes — build & verification evidence

Evidence log for the v0.1.0 implementation, per requirements §10. **No credentials appear in this
file** — the test server's credentials live only in the private handoff doc (git-ignored).

- **Date:** 2026-07-19
- **Repo:** `/home/darnr/interactive-input`
- **Artifact:** `target/interactive-input.hpi` — 247,108 bytes — SHA-256 `40b46e4b515943312f61fe2b1124678f54defb25d4e01190a57eb7343d9ca8bf`

---

## 1. Verified toolchain & platform versions

| Component | Version | How verified |
|---|---|---|
| JDK (build) | OpenJDK **21.0.11 LTS** | `java -version` |
| Bytecode target | Java **17** | `maven.compiler.release=17` in `pom.xml` |
| Apache Maven | **3.9.9** | `mvn -v` |
| Jenkins core (test server) | **2.555.2** | `curl -I` → `X-Jenkins: 2.555.2` |
| Parent POM | `plugin` **5.31** | `pom.xml` |
| Plugin BOM | `bom-2.555.x` **6715.v52b_c00222d1e** | `pom.xml` |
| `pipeline-input-step` (server) | **560.v56198a_642157** | upgraded on server via Update Center |

## 2. Local build result

```bash
mvn -B -ntp clean verify
```

- **BUILD SUCCESS.** Tests: **39 run, 0 failures, 0 errors, 1 skipped** (SpotBugs clean).
- Per-class: MarkdownRenderer 5, QuestionModel 8, AskInteractiveStep 4, JcascRoundTrip 2,
  QuestionStorePermissions/SLA 5, RestApi 6, InputStepBridge 4, auto-injected harness 5 (1 skipped).

## 3. Live verification on the test Jenkins (`http://<test-jenkins>/`)

> Internal host/IP redacted for public publication; the actual address is in the private handoff doc (§5.4).

All authenticated calls used an API token via `curl --noproxy '*'` (server is on the local network).
Responses summarised; secrets omitted.

| # | Action | Result |
|---|---|---|
| 1 | Confirm core version | `X-Jenkins: 2.555.2` |
| 2 | Upgrade `pipeline-input-step` to 560 | Deployed from Update Center (Script Console); confirmed via plugin manager |
| 3 | Install `interactive-input.hpi` | Copied into `$JENKINS_HOME/plugins/` via Script Console (see deviation D2), loaded |
| 4 | `GET /interactive-input/api/v1/health` | `200 {"status":"ok","pending":0}` |
| 5 | Bell assets served | `GET .../bell.js` → `200`, `.../bell.css` → `200` |
| 6 | **`askInteractive` job** (`ii-askinteractive-livetest`) | Paused at step; appeared in `GET /questions`; answered via `POST …/answer`; build **resumed → SUCCESS** |
| 7 | Markdown context render | `contextHtml` returned sanitised HTML |
| 8 | **XSS check** via `POST /preview` | `<script>alert(1)</script>` → `&lt;script&gt;…` (escaped, inert) |
| 9 | `?all=true` without admin / with admin | admin-gated as designed |
| 10 | **`inputStepBridge`** (`ii-bridge-livetest`) | Native `input` mirrored as `bridged:true`; answered mirror via REST → native step **proceeded** (`Approved by darnr`) → build **SUCCESS** |
| 11 | **Sample project `hitl-input-test` (V5)** — regression with plugin active | Build **#13 SUCCESS** (~10 min). Cursor-SDK agent asked 2 questions via native parameterized `input`; both **surfaced in the bell as `bridged:true` with a deep link** (parameterized → read-only, by design). Answered Q1 (`staging`) via the execution API and Q2 (`yes`) via native HTTP `POST …/input/<id>/proceed` → **HTTP 200**. Agent logged `[hitl_agent] status=finished`; `deploy_config.txt` written (`environment=staging`, `feature_x=yes`). After both inputs settled the bridge **auto-dropped the mirrors** (`/questions?all=true` → `count:0`, `/health` → `pending:0`). |

**Acceptance checklist (§9.3 / delegator V-table) status:** V1 ✅, V2 ✅, V3 ✅, V4 ✅ (via Script
Console, D2), **V5 ✅ (sample `hitl-input-test` build #13 → SUCCESS with plugin active; see row 11)**,
V6 ✅ (bell + assets), V7 ✅ (modal assets + all modal-backing REST endpoints verified
live; full visual click-through needs an interactive browser session), V8 ✅ (curl examples in
README), V9 ✅ (permission checks unit-tested + admin gating verified live), V11 ✅, V12 ✅.

## 4. Deviations from the spec (with justification)

- **D1 — Markdown escaping API.** Spec suggested a `StrictEscapesExtension` for commonmark, which
  does not exist in `commonmark-java`. Used the supported `HtmlRenderer.escapeHtml(true)` +
  `sanitizeUrls(true)` + `percentEncodeUrls(true)` instead. Same security outcome (verified live, §3 #8).
- **D2 — HPI install method.** `POST /pluginManager/uploadPlugin` returned HTTP 500 for the
  multipart upload in this environment. Worked around by copying the HPI into
  `$JENKINS_HOME/plugins/` via the Script Console (equivalent effect; plugin loads identically).
- **D3 — `workflow-cps` scope.** Moved to `test` scope. The runtime code is execution-engine
  agnostic (only `workflow-step-api`), so this avoids forcing a `workflow-cps` version floor on
  installs and resolved a server/BOM version mismatch (4331 vs 4350) without a server upgrade.
- **D4 — Stapler getter-routing.** The REST tree 404'd until `@StaplerAccessibleType` was added to
  the intermediate `Api` container class (getter-only nodes are not traversed by
  `jenkins.security.stapler.TypedFilter`). Documented in the requirements Learnings section (§16).

## 5. Provisional decisions on delegator open questions (§14)

- **Q4 (dashboard tile):** deferred to v0.2; flag present but `false` by default.
- **Q5 (`inputStepBridge` default):** **`false`** (opt-in) — safest default; operators enable per policy.
- **Q3/Q6 (publishing/hosting):** assumed OSS `plugins.jenkins.io` under `jenkinsci/…`; `pom.xml`
  SCM/URLs point there. Credentials remain out of the repo. Confirm before first push.

## 6. Evidence bibliography (URLs opened to verify claims)

- Plugin parent POM releases — https://github.com/jenkinsci/plugin-pom
- Jenkins plugin BOM — https://github.com/jenkinsci/bom
- Pipeline: Input Step source — https://github.com/jenkinsci/pipeline-input-step-plugin
- `pipeline-input-step` 560 release — https://github.com/jenkinsci/pipeline-input-step-plugin/releases/tag/560.v56198a_642157
- commonmark-java — https://github.com/commonmark/commonmark-java
- Configuration as Code — https://github.com/jenkinsci/configuration-as-code-plugin
- Jenkins artifact repo — https://repo.jenkins-ci.org/public/

## 7. Corporate proxy note

Behind Nokia's network, `~/.m2/settings.xml` must route through the corporate proxy (host
`<corp-proxy-host>`, port `8080`) with `nonProxyHosts` including the test Jenkins host, and add
`https://repo.jenkins-ci.org/public/` as a repository. The local Maven (`~/.build-tools/`) and
`env.sh` set `JAVA_HOME`/`M2_HOME` for reproducible builds.

## 8. Open follow-ups

- Investigate the `uploadPlugin` HTTP 500 (D2) for a cleaner CI deploy path.
- v0.2: parameterised-input answering in-modal, push notifications, escalation delivery, dashboard tile.

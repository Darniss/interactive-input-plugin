# Jenkins plugin hosting & Marketplace readiness

This document tracks readiness for hosting `interactive-input` in the
`jenkinsci` GitHub organization and publishing to the Jenkins Update Center
("Marketplace"). It maps every gate in the hosting process to its current
status and the supporting evidence, then lists the manual steps that only the
maintainer's account can perform.

Legend: **DONE** = satisfied in this repo · **MANUAL** = maintainer account/process
action outside the code · **AUTO** = performed by the Jenkins hosting bot.

---

## 1. Gate-by-gate status

### 1.1 Human review queue — *waiting, not code*
Status: **N/A (process)**. A hosting request sits in a reviewer queue for a few
days; longer if there are back-and-forth comments. Nothing to fix in code. To
minimize round-trips we have pre-satisfied every automated check below so the
first bot pass is green (fewer comments → shorter queue time).

### 1.2 Automated Jenkins Security Scan
The scan is a CodeQL run with the `jenkins-infra/jenkins-codeql` query pack
(CSRF, XSS, SSRF, path traversal, plaintext secrets, missing permission checks).
We added the same workflow the bot uses so it runs on every push/PR:
`.github/workflows/jenkins-security-scan.yml`.

| Common flag | Our status | Evidence |
|---|---|---|
| Missing CSRF protection (`@POST`/`@RequirePOST`) | **DONE** | Every state-changing endpoint is annotated: `doAnswer`, `doAbort`, `doPreview` all carry `@RequirePOST` (→ CSRF crumb enforced). Read-only endpoints (`doHealth`, `doIndex` list/detail) are GET by design. See `rest/ApiRootAction.java`. |
| Plaintext passwords / secrets | **DONE** | No password/secret/token literals in `src/main/java`. The plugin stores no credentials; it never handles Jenkins `Secret` values. |
| Outdated dependency versions | **DONE** | All plugin deps are pinned to the `io.jenkins.tools.bom:bom-2.555.x` line; `pipeline-input-step` pinned to `560.v56198a_642157`; `commonmark 0.24.0`. Dependabot (`.github/dependabot.yml`) keeps them current. |
| XSS in rendered markdown | **DONE (justified)** | Context/preview markdown is rendered server-side with commonmark configured to **escape raw HTML** and sanitize URLs; the client inserts it via a single controlled sink. See `markdown/MarkdownRenderer.java` and `SECURITY.md`. |
| Missing permission check | **DONE** | 12 explicit permission-check sites. REST mirrors `pipeline-input-step` permissions (`Job/Build` to answer/abort, `Item/Read` to view); admin-only for the global `?all=true` listing; no existence leaks (404 instead of 403). |

Handling any *new* finding: the scan **honors inline suppressions** (its SARIF
post-processing deletes results with a `suppressions` entry). A genuine false
positive can therefore be annotated with a CodeQL suppression comment plus a
justification, and it will not appear in the report. Only suppress with a
written reason; unexplained suppressions invite a slower manual audit.

Note on the anonymous `GET /interactive-input/api/v1/health` endpoint: it is an
intentional liveness probe returning only `{"status":"ok","pending":<int>}` — an
aggregate count, no job names, IDs, or content. This is standard for Jenkins
liveness endpoints and is not a permission-bypass. Rationale documented here and
in `SECURITY.md` in case a reviewer asks.

### 1.3 Hosting Checker bot (metadata)
The `HostingChecker` blocks approval until every "Required" item is green.

| Check | Our status | Evidence |
|---|---|---|
| `groupId` is `io.jenkins.plugins` (not the legacy `org.jenkins-ci.plugins`) | **DONE** | `pom.xml` |
| `artifactId` has no `jenkins`/`hudson`, matches repo | **DONE** | `interactive-input` → repo `interactive-input-plugin` |
| Recent parent POM | **DONE** | `org.jenkins-ci.plugins:plugin:5.31` |
| `jenkins.version` is a real, supported baseline | **DONE** | `2.555.2` (matched by `bom-2.555.x`) |
| `<name>`, `<description>` present and meaningful | **DONE** | `pom.xml` |
| `<licenses>` present | **DONE** | MIT (`pom.xml`, `LICENSE`) |
| `<developers>` with a valid `<id>` | **DONE** | `id=darniss` — **this id must match the maintainer's jenkins.io/GitHub account (see §2)** |
| `<scm>` uses HTTPS and points at the jenkinsci repo | **DONE** | connection/developerConnection/url/tag all set to `jenkinsci/interactive-input-plugin` |
| No redundant `<repositories>`/`<pluginRepositories>` | **DONE** | Removed; the parent POM already provides `repo.jenkins-ci.org` (verified via `help:effective-pom`). |
| `Jenkinsfile` for ci.jenkins.io | **DONE** | `buildPlugin(...)` on Linux/JDK21 + Windows/JDK17 |
| Contributor has logged into Artifactory ≥ once | **MANUAL** | See §2 — account action, cannot be done in code. |

### 1.4 Dependency / versioning gotchas (e.g. `jakarta.xml.bind` classloading)
Status: **DONE — verified clean.** The koji-scm case broke because a transitive
`jakarta.xml.bind` landed on the plugin's *runtime* classpath. Our dependency
tree has **no** JAXB / `jakarta.xml.bind` / activation artifact at compile or
runtime scope — every such artifact is `:test` only (test-harness / jenkins-war),
and `jenkins-core` is `provided`. There are **zero** "omitted for conflict"
entries at compile/runtime. Regenerate the proof with:

```bash
mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:tree -Dverbose \
  | grep -iE 'jaxb|jakarta\.xml\.bind|activation|omitted for conflict'
# → all matches are ':test'; none at compile/runtime
```

The only third-party library we bundle into the HPI is `commonmark` (declared in
`hpi.bundledArtifacts`), which has no transitive dependencies.

### 1.5 Repo restructuring after approval
Status: **MANUAL (post-approval)** — see §3.

---

## 2. Manual prerequisites (maintainer account — do these first)

These cannot be done in the repository; they require the maintainer's identity.

1. **Create/confirm a Jenkins community account** at <https://accounts.jenkins.io>.
   The same SSO identity is used for Jira, GitHub team membership, and Artifactory.
2. **Log into Artifactory at least once**: sign in at
   <https://repo.jenkins-ci.org/> with the jenkins.io account. The Hosting
   Checker verifies each listed contributor has an Artifactory account; a
   never-logged-in account fails the check.
3. **GitHub username** ready for the hosting request; it must be able to accept
   the invitation to the `jenkinsci` organization.
4. **Align the `<developer><id>`** in `pom.xml` (currently `darniss`) with the
   jenkins.io username, or update it before submitting.

## 3. Submission & post-approval flow

1. Push this repository to your **personal** GitHub as
   `interactive-input-plugin` (repo name = `<artifactId>-plugin`).
2. Open a **hosting request** following
   <https://www.jenkins.io/doc/developer/publishing/requesting-hosting/>
   (a GitHub issue from the hosting-request template). Provide the repo URL,
   the new plugin name, and your GitHub username.
3. The **Hosting Checker** and **Security Scan** bots comment automatically.
   Because §1.2–1.4 are pre-satisfied, expect a green first pass; fix or justify
   anything new, then re-run.
4. After a human reviewer approves, the bot **forks the repo into `jenkinsci`**
   and grants you permissions (via repository-permissions-updater).
5. **Repo restructuring (easy to get wrong):** stop pushing to your original
   repo. Either delete it and fork `jenkinsci/interactive-input-plugin`, or
   re-point your local clone's `origin` to the new jenkinsci repo. All future
   PRs/releases go through the jenkinsci-owned copy.
6. **First release** to the Update Center is done from ci.jenkins.io using the
   automated release (JEP-229) / Maven release process. Drop `-SNAPSHOT` for the
   release tag; keep `-SNAPSHOT` on the development version.

## 4. Local pre-submission checklist

```bash
# 1) Full build must be green (unit tests + SpotBugs + HPI)
mvn -B clean verify

# 2) No SNAPSHOT deps other than the project's own version
mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:tree | grep -i snapshot || echo "clean"

# 3) No JAXB/jakarta.xml.bind at compile/runtime (see §1.4)
mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:tree -Dverbose \
  | grep -iE 'jaxb|jakarta\.xml\.bind' | grep -viE ':test' || echo "clean"
```

All three pass in this repository as of the current build (see `SESSION_NOTES.md`).

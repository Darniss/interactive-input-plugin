# Bill of Materials (BOM)

Software Bill of Materials for the **Interactive Input** Jenkins plugin. It records the exact
components that make up the artifact and the toolchain used to build it, so the build is auditable
and reproducible.

- **Generated:** 2026-07-19
- **Method:** `mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:tree` against the resolved `bom-2.555.x` platform.
- **Regenerate:** see [How to regenerate](#how-to-regenerate).

---

## 1. Artifact identity

| Field | Value |
|---|---|
| Group ID | `io.jenkins.plugins` |
| Artifact ID | `interactive-input` |
| Version | `0.1.0` (`0.1.0-SNAPSHOT` in development) |
| Packaging | `hpi` (Jenkins plugin) |
| Artifact | `target/interactive-input.hpi` |
| Size | 247,108 bytes |
| SHA-256 | `40b46e4b515943312f61fe2b1124678f54defb25d4e01190a57eb7343d9ca8bf` |
| License | MIT |

> The SHA-256 above is for the build produced on 2026-07-19; it changes on every rebuild. Recompute
> with `sha256sum target/interactive-input.hpi`.

---

## 2. Build toolchain

| Component | Version | Role |
|---|---|---|
| JDK (build) | Eclipse Temurin / Red Hat OpenJDK **21.0.11 LTS** | compiler + test runtime |
| Bytecode target | **Java 17** (`maven.compiler.release=17`) | minimum runtime |
| Apache Maven | **3.9.9** | build tool |
| Parent POM | `org.jenkins-ci.plugins:plugin` **5.31** | Jenkins plugin conventions |
| Plugin BOM | `io.jenkins.tools.bom:bom-2.555.x` **6715.v52b_c00222d1e** | dependency alignment |
| `maven-hpi-plugin` | **3.1814.v77d15159f9b_d** | HPI packaging (overridden for Java 21 build) |
| Jenkins core (target) | **2.555.2** | `provided` platform |

---

## 3. Direct runtime dependencies

These are declared in `pom.xml`. Versions marked *(BOM)* are governed by `bom-2.555.x`; the others
are explicitly pinned.

| Artifact | Version | Scope | License | Why it's here |
|---|---|---|---|---|
| `org.jenkins-ci.plugins.workflow:workflow-step-api` | 724.v538c2362b_dfb_ *(BOM)* | compile | MIT | Defines `Step`/`StepExecution` for `askInteractive`. |
| `org.jenkins-ci.plugins.workflow:workflow-job` | 1571.1580.v18e46842c125 *(BOM)* | compile | MIT | `WorkflowRun` / pipeline job model. |
| `org.jenkins-ci.plugins.workflow:workflow-support` | 1015.v785e5a_b_b_8b_22 *(BOM)* | compile | MIT | `AbstractStepExecutionImpl` durable base. |
| `org.jenkins-ci.plugins.workflow:workflow-durable-task-step` | 1479.v56e587f413a_7 *(BOM)* | compile | MIT | Durable-step foundation (survives restart). |
| `org.jenkins-ci.plugins:pipeline-input-step` | **560.v56198a_642157** (pinned) | compile | MIT | **Hard requirement** — modal surface + `input` bridge target. |
| `org.jenkins-ci.plugins:structs` | 362.va_b_695ef4fdf9 *(BOM)* | compile | MIT | `@DataBoundConstructor` describable binding. |
| `org.jenkins-ci.plugins:script-security` | 1402.1405.vc96e74964250 *(BOM)* | compile | MIT | Transitive of input-step; sandbox integration. |
| `io.jenkins:configuration-as-code` | 2100.vb_fd699d2a_09c *(BOM)* | compile *(optional)* | MIT | JCasC support — **optional** at runtime. |
| `org.commonmark:commonmark` | **0.24.0** (pinned) | compile | BSD-2-Clause | Safe Markdown → HTML (escaped). **Bundled in the HPI.** |

---

## 4. Notable transitive runtime dependencies

Pulled in by the direct dependencies above and present at runtime (provided by their own plugins /
Jenkins core unless bundled):

| Artifact | Version | License | Via |
|---|---|---|---|
| `org.jenkins-ci.plugins.workflow:workflow-api` | 1413.v2ff1a_5e720fa_ | MIT | workflow-job |
| `io.jenkins.plugins:ionicons-api` | 94.vcc3065403257 | MIT | workflow-job |
| `org.jenkins-ci.plugins:scm-api` | 728.vc30dcf7a_0df5 | MIT | workflow-support |
| `io.jenkins.plugins:caffeine-api` | 3.2.4-208.v7e2da_a_7db_82b_ | Apache-2.0 | workflow-support |
| `org.jenkins-ci.plugins:durable-task` | 686.v80ff80875b_82 | MIT | workflow-durable-task-step |
| `org.jenkins-ci.plugins:credentials` | 1506.v948b_b_b_7dec44 | MIT | pipeline-input-step |
| `org.jenkins-ci.plugins:bouncycastle-api` | 2.30.1.84-291.v9f17b_21896e2 | MIT / BC | credentials |
| `org.kohsuke:groovy-sandbox` | 1.34.1 | MIT | script-security |
| `io.jenkins.plugins:asm-api` (ASM 9.10.x) | 9.10.1-216.va_9256d3b_844b_ | BSD-3-Clause | scm-api |
| `org.jenkins-ci.plugins:antisamy-markup-formatter` | 173.v680e3a_b_69ff3 | MIT | configuration-as-code (optional) |
| `io.jenkins.plugins:snakeyaml-api` | 2.5-149.v72471e9c6371 | Apache-2.0 | configuration-as-code (optional) |

> Only `commonmark` (and its zero transitive deps) is physically bundled into `WEB-INF/lib` of the
> HPI. Every other runtime artifact is contributed by Jenkins core or by a separately-installed
> plugin dependency, per Jenkins' plugin classloading model.

---

## 5. Provided platform (Jenkins core 2.555.2 — not bundled)

The plugin compiles against but does **not** ship these; the controller provides them at runtime.
Selected highlights:

| Artifact | Version | License |
|---|---|---|
| `org.jenkins-ci.main:jenkins-core` | 2.555.2 | MIT |
| `org.kohsuke.stapler:stapler` | 2076.v1b_a_c12445eb_e | BSD-3-Clause |
| `com.thoughtworks.xstream:xstream` | 1.4.21 | BSD-3-Clause |
| `com.google.guava:guava` | 33.5.0-jre | Apache-2.0 |
| `org.springframework.security:spring-security-web` | 6.5.9 | Apache-2.0 |
| `jakarta.servlet:jakarta.servlet-api` | 5.0.0 | EPL-2.0 / GPL-2.0-CE |
| `org.codehaus.groovy:groovy-all` | 2.4.21 | Apache-2.0 |
| `com.github.spotbugs:spotbugs-annotations` | 4.9.8 | LGPL-2.1 |

---

## 6. Test-only dependencies (not shipped)

Used to compile/run the JUnit 5 suite; excluded from the HPI.

| Artifact | Version | License | Purpose |
|---|---|---|---|
| `org.jenkins-ci.main:jenkins-test-harness` | 2537.v48fd29a_7070d | MIT | `JenkinsRule` integration tests |
| `org.junit.jupiter:junit-jupiter` | 6.0.1 | EPL-2.0 | JUnit 5 engine |
| `org.jenkins-ci.plugins.workflow:workflow-cps` | 4350.vcc65d4958821 | MIT | author `CpsFlowDefinition` scripts in tests only |
| `org.jenkins-ci.plugins.workflow:workflow-basic-steps` | 1098.v808b_fd7f8cf4 | MIT | `echo`/`input` steps in test pipelines |
| `io.jenkins.configuration-as-code:test-harness` | 2100.vb_fd699d2a_09c | MIT | JCasC round-trip test support |

> `workflow-cps` is deliberately **test-scoped**: the plugin's runtime is execution-engine agnostic
> (it only needs `workflow-step-api`), so installs are not forced onto a `workflow-cps` floor.

---

## 7. License summary

| License | Applies to |
|---|---|
| **MIT** | This plugin, Jenkins core, and the great majority of Jenkins plugin dependencies. |
| **BSD-2-Clause** | `commonmark` (the only bundled third-party runtime library). |
| **BSD-3-Clause** | Stapler, XStream, ASM. |
| **Apache-2.0** | Guava, Spring, Caffeine, SnakeYAML, OWASP HTML sanitizer. |
| **EPL / LGPL** | Test-only (JUnit) and build-only (SpotBugs annotations) components. |

No copyleft (GPL) code is bundled into the distributed HPI. The single bundled third-party library
(`commonmark`, BSD-2-Clause) is compatible with the plugin's MIT license and with redistribution on
the Jenkins Update Center.

---

## How to regenerate

```bash
source ~/.build-tools/env.sh   # JDK 17+ and Maven on PATH
cd interactive-input

# Full resolved tree
mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.6.1:tree -DoutputFile=deptree.txt

# Artifact hash
sha256sum target/interactive-input.hpi

# (Optional) CycloneDX machine-readable SBOM
mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
# -> target/bom.json  (import into your SCA / vulnerability scanner)
```

For automated supply-chain scanning, generate the CycloneDX `bom.json` and feed it to your SCA
tool (e.g. OWASP Dependency-Track, Grype, Trivy).

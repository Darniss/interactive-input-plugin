package io.jenkins.plugins.interactiveinput.rest;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.UnprotectedRootAction;
import io.jenkins.plugins.interactiveinput.bridge.InputStepBridge;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import io.jenkins.plugins.interactiveinput.util.MarkdownRenderer;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.security.stapler.StaplerAccessibleType;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Versioned JSON REST API rooted at {@code /interactive-input/api/v1/} (§6.3, §8.6).
 *
 * <p>This is an {@link UnprotectedRootAction} so the anonymous {@code /health} liveness probe is
 * reachable; every other endpoint enforces permissions explicitly. Mutating endpoints are
 * {@code @RequirePOST} and therefore also require the standard Jenkins CSRF crumb (enforced by
 * {@code CrumbFilter} at the framework level). Responses are always JSON via {@code net.sf.json}.
 *
 * <p>Routing (Stapler getter/do-method traversal):
 * <pre>
 *   GET  /interactive-input/api/v1/questions              -&gt; Questions#doIndex        (Overall/Read; ?all=true =&gt; Administer)
 *   GET  /interactive-input/api/v1/questions/{id}          -&gt; QuestionEndpoint#doIndex  (Item.READ, else 404)
 *   POST /interactive-input/api/v1/questions/{id}/answer   -&gt; QuestionEndpoint#doAnswer (Item.BUILD, else 403; 409 if settled)
 *   POST /interactive-input/api/v1/questions/{id}/abort    -&gt; QuestionEndpoint#doAbort  (Item.BUILD, else 403; 409 if settled)
 *   POST /interactive-input/api/v1/preview                 -&gt; V1#doPreview              (Overall/Read; safe markdown preview)
 *   GET  /interactive-input/api/v1/health                  -&gt; V1#doHealth               (anonymous)
 * </pre>
 */
@Extension
public class ApiRootAction implements UnprotectedRootAction {

    public static final String URL_NAME = "interactive-input";

    private static final Logger LOGGER = Logger.getLogger(ApiRootAction.class.getName());

    @Override
    @CheckForNull
    public String getIconFileName() {
        return null;
    }

    @Override
    @CheckForNull
    public String getDisplayName() {
        return null;
    }

    @Override
    @NonNull
    public String getUrlName() {
        return URL_NAME;
    }

    public Api getApi() {
        return new Api();
    }

    static boolean apiEnabled() {
        return InteractiveInputGlobalConfig.featuresOrDefault().isRestApi();
    }

    @CheckForNull
    static HttpResponse apiDisabledOrNull() {
        return apiEnabled() ? null : JsonHttpResponse.error(404, "Interactive Input REST API is disabled");
    }

    @NonNull
    static JSONObject questionJson(@NonNull Question q, boolean includeAnswer) {
        JSONObject o = q.toJson(includeAnswer, System.currentTimeMillis());
        o.put("contextHtml", MarkdownRenderer.render(q.getContextMd()));
        // Whether the current viewer may actually answer (lock-to-build-starter aware). The modal
        // uses this to lock its controls for a viewer who can see but not answer.
        o.put("canAnswer", QuestionStore.get().canAnswerEffective(q));
        return o;
    }

    // ==========================================================================================
    // /api
    // ==========================================================================================
    // Stapler's getter-routing hardening (jenkins.security.stapler.TypedFilter) only traverses a
    // getter (here getApi()) when its return type is a "Stapler-relevant" node: one that is
    // @StaplerAccessibleType, implements a Stapler interface, or exposes at least one web method.
    // Api is a pure container (only getV1()), so without this annotation getApi() is blocked and the
    // entire /interactive-input/api/** tree 404s. V1/Questions already have web methods, and
    // QuestionEndpoint is reached via getDynamic (which bypasses this filter), so only Api needs it.
    @StaplerAccessibleType
    public static class Api {
        public V1 getV1() {
            return new V1();
        }
    }

    // ==========================================================================================
    // /api/v1
    // ==========================================================================================
    public static class V1 {

        public Questions getQuestions() {
            return new Questions();
        }

        /** GET /health — anonymous liveness probe. */
        public HttpResponse doHealth() {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            JSONObject o = new JSONObject();
            o.put("status", "ok");
            o.put("pending", QuestionStore.get().listAll().size());
            return new JsonHttpResponse(200, o);
        }

        /** POST /preview — render markdown to safe HTML for the modal's free-text preview. */
        @RequirePOST
        public HttpResponse doPreview(StaplerRequest2 req) {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            Jenkins.get().checkPermission(Jenkins.READ);
            JSONObject body = readBody(req);
            String md = body.optString("markdown", "");
            JSONObject o = new JSONObject();
            o.put("html", MarkdownRenderer.render(md));
            return new JsonHttpResponse(200, o);
        }
    }

    // ==========================================================================================
    // /api/v1/questions
    // ==========================================================================================
    public static class Questions {

        /**
         * GET /questions — list WAITING questions visible to the caller.
         *
         * <p>Scoping:
         * <ul>
         *   <li>{@code ?job=<fullName>} — questions for one job the caller may answer (per-project
         *       notification centre). Requires {@code Item.READ} on that job; 404 otherwise (no leak).</li>
         *   <li>{@code ?job=<fullName>&build=<n>} — every readable question (any status, with answers)
         *       for one build (per-build audit view).</li>
         *   <li>{@code ?all=true} — every WAITING question (requires {@code Overall/Administer}).</li>
         *   <li>default — every WAITING question the caller may answer, across all jobs.</li>
         * </ul>
         */
        public HttpResponse doIndex(StaplerRequest2 req) {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            Jenkins j = Jenkins.get();
            if (!j.hasPermission(Jenkins.READ)) {
                return JsonHttpResponse.error(403, "Overall/Read required");
            }
            QuestionStore store = QuestionStore.get();
            String jobParam = req.getParameter("job");
            boolean all = "true".equalsIgnoreCase(req.getParameter("all"));
            List<Question> list;
            boolean includeAnswer = false;
            if (jobParam != null && !jobParam.isEmpty()) {
                Job<?, ?> job = j.getItemByFullName(jobParam, Job.class);
                if (job == null || !job.hasPermission(Item.READ)) {
                    return JsonHttpResponse.error(404, "No such job: " + jobParam);
                }
                String buildParam = req.getParameter("build");
                if (buildParam != null && !buildParam.isEmpty()) {
                    int buildNumber;
                    try {
                        buildNumber = Integer.parseInt(buildParam.trim());
                    } catch (NumberFormatException e) {
                        return JsonHttpResponse.error(400, "build must be an integer");
                    }
                    list = store.listForBuild(jobParam, buildNumber);
                    includeAnswer = true; // audit view: show what was chosen
                } else {
                    // Self-heal bridged mirrors for this pipeline so answers made through the native
                    // input UI (or a mirror just answered here) drop out within one poll instead of
                    // waiting for the 30s ticker. reconcile() is a cheap near-no-op when the bridge is
                    // off, and must never break the list response.
                    try {
                        InputStepBridge.get().reconcile(jobParam);
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.FINE, e, () -> "bridge reconcile failed for " + jobParam);
                    }
                    list = store.listNotificationsForJob(jobParam);
                }
            } else if (all) {
                if (!j.hasPermission(Jenkins.ADMINISTER)) {
                    return JsonHttpResponse.error(403, "Overall/Administer required for ?all=true");
                }
                list = store.listAll();
            } else {
                list = store.listNotifications();
            }
            JSONArray arr = new JSONArray();
            for (Question q : list) {
                arr.add(questionJson(q, includeAnswer));
            }
            JSONObject o = new JSONObject();
            o.put("count", arr.size());
            o.put("questions", arr);
            return new JsonHttpResponse(200, o);
        }

        public QuestionEndpoint getDynamic(String id) {
            return new QuestionEndpoint(id);
        }
    }

    // ==========================================================================================
    // /api/v1/questions/{id}
    // ==========================================================================================
    public static class QuestionEndpoint {

        private final String id;

        public QuestionEndpoint(String id) {
            this.id = id;
        }

        /** GET /questions/{id} — detail. 404 if not found or not readable (avoids existence leak). */
        public HttpResponse doIndex() {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            QuestionStore store = QuestionStore.get();
            Question q = store.get(id);
            if (q == null || !store.canView(q)) {
                return JsonHttpResponse.error(404, "No such question: " + id);
            }
            return new JsonHttpResponse(200, questionJson(q, true));
        }

        /** POST /questions/{id}/answer — submit an answer. */
        @RequirePOST
        public HttpResponse doAnswer(StaplerRequest2 req) {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            QuestionStore store = QuestionStore.get();
            Question q = store.get(id);
            if (q == null) {
                return JsonHttpResponse.error(404, "No such question: " + id);
            }
            if (!store.canAnswerEffective(q)) {
                return JsonHttpResponse.error(403, "Job/Build permission (or submitter membership) required");
            }
            if (q.getStatus().isTerminal()) {
                return JsonHttpResponse.error(409, "Question already " + q.getStatus());
            }
            JSONObject body = readBody(req);
            String choiceId = optString(body, "choiceId");
            String freeText = optString(body, "freeText");

            String validationError = validateAnswer(q, choiceId, freeText);
            if (validationError != null) {
                return JsonHttpResponse.error(400, validationError);
            }
            try {
                store.answer(id, choiceId, freeText, QuestionStore.currentUserId(), QuestionStore.SOURCE_REST);
            } catch (IllegalStateException e) {
                return JsonHttpResponse.error(409, e.getMessage());
            }
            return new JsonHttpResponse(200, questionJson(q, true));
        }

        /** POST /questions/{id}/abort — cancel the input (delivers an abort to the pipeline). */
        @RequirePOST
        public HttpResponse doAbort() {
            HttpResponse disabled = apiDisabledOrNull();
            if (disabled != null) {
                return disabled;
            }
            QuestionStore store = QuestionStore.get();
            Question q = store.get(id);
            if (q == null) {
                return JsonHttpResponse.error(404, "No such question: " + id);
            }
            if (!store.canAnswerEffective(q)) {
                return JsonHttpResponse.error(403, "Job/Build permission (or submitter membership) required");
            }
            if (q.getStatus().isTerminal()) {
                return JsonHttpResponse.error(409, "Question already " + q.getStatus());
            }
            try {
                store.abort(id, QuestionStore.currentUserId(), QuestionStore.SOURCE_REST);
            } catch (IllegalStateException e) {
                return JsonHttpResponse.error(409, e.getMessage());
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("id", id);
            return new JsonHttpResponse(200, o);
        }
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    /** Validate an answer against the question; returns an error message or {@code null} if valid. */
    @CheckForNull
    static String validateAnswer(@NonNull Question q, @CheckForNull String choiceId, @CheckForNull String freeText) {
        if (choiceId != null && !choiceId.isEmpty()) {
            if (io.jenkins.plugins.interactiveinput.model.Answer.DENY_CHOICE_ID.equals(choiceId)) {
                return null;
            }
            for (Choice c : q.getChoices()) {
                if (c.getId().equals(choiceId)) {
                    return null;
                }
            }
            return "Unknown choiceId: " + choiceId;
        }
        if (freeText != null && !freeText.isEmpty()) {
            if (!q.isAllowFreeText()) {
                return "This question does not allow free-text answers";
            }
            return null;
        }
        return "An answer must include a choiceId or freeText";
    }

    @CheckForNull
    private static String optString(@NonNull JSONObject o, @NonNull String key) {
        if (!o.containsKey(key) || o.get(key) == null) {
            return null;
        }
        String s = o.getString(key);
        return s.isEmpty() ? null : s;
    }

    @NonNull
    static JSONObject readBody(@NonNull StaplerRequest2 req) {
        JSONObject o = new JSONObject();
        String ct = req.getContentType();
        if (ct != null && ct.toLowerCase(Locale.ROOT).contains("application/json")) {
            try (BufferedReader r = req.getReader()) {
                if (r != null) {
                    String body = r.lines().collect(Collectors.joining("\n"));
                    if (!body.trim().isEmpty()) {
                        return JSONObject.fromObject(body);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Malformed or unavailable JSON body (I/O error, UncheckedIOException from lines(),
                // or JSONException from parsing): fall through and return an empty object.
            }
            return o;
        }
        putIfPresent(o, "choiceId", req.getParameter("choiceId"));
        putIfPresent(o, "freeText", req.getParameter("freeText"));
        putIfPresent(o, "markdown", req.getParameter("markdown"));
        return o;
    }

    private static void putIfPresent(@NonNull JSONObject o, @NonNull String key, @CheckForNull String value) {
        if (value != null) {
            o.put(key, value);
        }
    }
}

package io.jenkins.plugins.interactiveinput.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.view.ReviewDocument;
import io.jenkins.plugins.interactiveinput.view.ReviewStatus;
import io.jenkins.plugins.interactiveinput.view.ViewStore;
import java.net.URL;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * REST tests for the {@code /views} endpoints: the {@code Overall/Read} + {@code Item.BUILD} permission
 * matrix, 404 existence-hiding, feature-flag gating, CSRF-crumbed mutations, and the security invariant
 * that HTML/code content is returned as raw text (never server-rendered) while markdown and comment
 * bodies are sanitised.
 */
@WithJenkins
class ViewRestApiTest {

    private static final String JOB = "job-a";
    private static final String BASE = "interactive-input/api/v1/";

    @Test
    void listRequiresOverallReadAndFiltersByItemRead(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        // No Overall/Read at all -> 403 at the endpoint gate.
        assertEquals(403, get(j.createWebClient(), j, BASE + "views").getStatusCode());

        // reader holds Item.READ -> sees the notify-enabled OPEN review.
        WebResponse readerResp = get(j.createWebClient().login("reader"), j, BASE + "views");
        assertEquals(200, readerResp.getStatusCode());
        assertEquals(1, json(readerResp).getInt("count"));
    }

    @Test
    void summaryJsonCarriesStarterAndBuildDeletedFlag(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        WebResponse resp = get(j.createWebClient().login("reader"), j, BASE + "views");
        assertEquals(200, resp.getStatusCode());
        JSONArray views = json(resp).getJSONArray("views");
        assertEquals(1, views.size());
        JSONObject v = views.getJSONObject(0);
        // createdBy is set server-side from the build cause; the bell renders it as "started by <user>".
        assertEquals("tester", v.getString("createdBy"), "the review's starter is exposed for the bell label");
        assertFalse(v.getBoolean("buildDeleted"), "a live review is not build-deleted");
    }

    @Test
    void detailIsHiddenWithoutItemRead(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        WebResponse ok = get(j.createWebClient().login("builder"), j, BASE + "views/v1");
        assertEquals(200, ok.getStatusCode());
        assertEquals("v1", json(ok).getString("id"));

        // Anonymous lacks Item.READ -> 404 (never reveal existence).
        assertEquals(404, get(j.createWebClient(), j, BASE + "views/v1").getStatusCode());
        // Unknown id -> 404.
        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "views/nope")
                        .getStatusCode());
    }

    @Test
    void featureFlagOffReturns404(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        cfg.getFeatures().setInteractiveView(false);
        cfg.save();

        assertEquals(
                404, get(j.createWebClient().login("reader"), j, BASE + "views").getStatusCode());
        assertEquals(
                404,
                get(j.createWebClient().login("builder"), j, BASE + "views/v1").getStatusCode());
    }

    @Test
    void commentRequiresBuildPermissionAndCrumb(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        // reader has Item.READ but not Item.BUILD -> cannot contribute.
        assertEquals(
                403, postJson(j.createWebClient().login("reader"), j, BASE + "views/v1/comments", "{\"body\":\"hi\"}"));
        // builder without a crumb -> CSRF rejected (403).
        assertEquals(
                403,
                postNoCrumb(j.createWebClient().login("builder"), j, BASE + "views/v1/comments", "{\"body\":\"hi\"}"));
        // builder with a crumb -> 200.
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/v1/comments",
                        "{\"body\":\"looks good\"}"));

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/v1"));
        assertEquals(1, detail.getJSONArray("comments").size());
    }

    @Test
    void editRespectsEditableFlagAndVersionsTheCopy(JenkinsRule j) throws Exception {
        secure(j);
        seed("ro", ReviewDocument.FORMAT_TEXT, "original", true, false); // not editable
        seed("rw", ReviewDocument.FORMAT_TEXT, "original", true, true); // editable

        assertEquals(
                409,
                postJson(j.createWebClient().login("builder"), j, BASE + "views/ro/edit", "{\"content\":\"x\"}"),
                "editing a non-editable review is a 409");

        assertEquals(
                200,
                postJson(j.createWebClient().login("builder"), j, BASE + "views/rw/edit", "{\"content\":\"revised\"}"));
        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/rw"));
        assertEquals(2, detail.getInt("currentVersion"));
        assertEquals("revised", detail.getString("content"));

        // The original version is still retrievable (edit-copy history).
        JSONObject v1 = json(get(j.createWebClient().login("builder"), j, BASE + "views/rw/raw?version=1"));
        assertEquals("original", v1.getString("content"));
    }

    @Test
    void editWithNoteRecordsItInTheVersionHistoryElseDefaults(JenkinsRule j) throws Exception {
        secure(j);
        seed("rw2", ReviewDocument.FORMAT_TEXT, "original", true, true); // editable

        // An AI course-correction can carry a summary note that lands in the version history (v2), so the
        // viewer's version dropdown shows why the edit happened rather than a generic "edited".
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/rw2/edit",
                        "{\"content\":\"revised by AI\",\"note\":\"Course-corrected: tightened wording\"}"));
        JSONArray versions = json(get(j.createWebClient().login("builder"), j, BASE + "views/rw2"))
                .getJSONArray("versions");
        assertEquals(
                "Course-corrected: tightened wording",
                versions.getJSONObject(1).getString("note"),
                "the supplied note is recorded on the new version");

        // An edit with no note keeps the default label, so legacy clients are unaffected.
        assertEquals(
                200,
                postJson(j.createWebClient().login("builder"), j, BASE + "views/rw2/edit", "{\"content\":\"again\"}"));
        JSONArray after = json(get(j.createWebClient().login("builder"), j, BASE + "views/rw2"))
                .getJSONArray("versions");
        assertEquals("edited", after.getJSONObject(2).getString("note"), "a note-less edit falls back to 'edited'");
    }

    @Test
    void decisionApprovesThenConflictsOnRepeat(JenkinsRule j) throws Exception {
        secure(j);
        seed("v1", ReviewDocument.FORMAT_TEXT, "text", true, false);

        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/v1/decision",
                        "{\"decision\":\"approve\"}"));
        assertEquals(ReviewStatus.APPROVED, ViewStore.get().get("v1").getStatus());

        assertEquals(
                409,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/v1/decision",
                        "{\"decision\":\"reject\"}"),
                "a second decision on a decided review is a 409");

        // An unknown decision verb is a 400.
        seed("v2", ReviewDocument.FORMAT_TEXT, "text", true, false);
        assertEquals(
                400,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/v2/decision",
                        "{\"decision\":\"maybe\"}"));
    }

    @Test
    void requestChangesSetsStatusAndReturnsCommentsForRegeneration(JenkinsRule j) throws Exception {
        secure(j);
        seed("c1", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);

        // A reviewer adds a line-anchored comment (line 3 = "Body line.")...
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/c1/comments",
                        "{\"body\":\"tighten this\",\"line\":3}"));

        // ...then clicks "Request changes". The decision resolves to CHANGES_REQUESTED and the response
        // carries the comments back so the pipeline can hand them to a generator for a regenerate loop.
        WebResponse resp = postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/c1/decision",
                "{\"decision\":\"request-changes\"}");
        assertEquals(200, resp.getStatusCode());
        JSONObject body = json(resp);
        assertEquals("CHANGES_REQUESTED", body.getString("status"));
        JSONArray comments = body.getJSONArray("comments");
        assertEquals(1, comments.size());
        assertEquals(3, comments.getJSONObject(0).getInt("line"));
        assertEquals(ReviewStatus.CHANGES_REQUESTED, ViewStore.get().get("c1").getStatus());
    }

    @Test
    void regenerateLoopEditsInPlaceWithNoteAfterRequestChanges(JenkinsRule j) throws Exception {
        secure(j);
        seed("rg", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, true); // editable

        // A reviewer comments then clicks "Request changes" -> the review becomes CHANGES_REQUESTED.
        JSONObject afterRoot = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rg/comments",
                "{\"body\":\"expand this\",\"line\":3}"));
        String rootId = afterRoot.getJSONArray("comments").getJSONObject(0).getString("id");
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/rg/decision",
                        "{\"decision\":\"request-changes\"}"));
        assertEquals(ReviewStatus.CHANGES_REQUESTED, ViewStore.get().get("rg").getStatus());

        // The regenerate-agent course-corrects the SAME review in place. CHANGES_REQUESTED must permit the
        // edit (it previously 409'd on any decided state), and the summary note lands in the version history.
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/rg/edit",
                        "{\"content\":\"# Title\\n\\nExpanded body line.\",\"note\":\"Regenerated: expanded per review\"}"),
                "editing a CHANGES_REQUESTED review is allowed for the regenerate loop");
        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/rg"));
        JSONArray versions = detail.getJSONArray("versions");
        assertEquals(
                "Regenerated: expanded per review",
                versions.getJSONObject(versions.size() - 1).getString("note"),
                "the AI summary note is recorded on the new version");
        assertEquals("CHANGES_REQUESTED", detail.getString("status"), "an in-place edit does not re-open the review");

        // The automation also threads a reply under the reviewer's comment (still allowed post-decision),
        // and the audit author stays the real, server-set identity.
        JSONObject afterReply = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rg/comments",
                "{\"body\":\"expanded the body\",\"line\":3,\"parentId\":\"" + rootId + "\",\"automated\":true}"));
        JSONObject reply = findReply(afterReply.getJSONArray("comments"));
        assertEquals(rootId, reply.getString("parentId"));
        assertEquals("builder", reply.getString("author"), "the audit author stays real for the automated reply");

        // A finally-decided (APPROVED) review remains read-only — the relaxation is scoped to the
        // "please course-correct" state only.
        seed("rgApproved", ReviewDocument.FORMAT_MARKDOWN, "# T\n\nB.", true, true);
        postJson(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rgApproved/decision",
                "{\"decision\":\"approve\"}");
        assertEquals(
                409,
                postJson(
                        j.createWebClient().login("builder"), j, BASE + "views/rgApproved/edit", "{\"content\":\"x\"}"),
                "an approved review stays read-only");
    }

    @Test
    void commentAndResolveResponsesCarryContentSoTheLeftPaneSurvives(JenkinsRule j) throws Exception {
        secure(j);
        seed("cc", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);

        // Req 3a: POST /comments must return the FULL document (content + renderedHtml) — not a summary —
        // so the client re-renders the detail pane without blanking it (the inline-comment "crash").
        WebResponse addResp = postJsonResponse(
                j.createWebClient().login("builder"), j, BASE + "views/cc/comments", "{\"body\":\"note\",\"line\":3}");
        assertEquals(200, addResp.getStatusCode());
        JSONObject added = json(addResp);
        assertEquals("# Title\n\nBody line.", added.getString("content"), "comment response must carry content");
        assertTrue(added.has("renderedHtml"), "markdown comment response must carry renderedHtml");
        String commentId = added.getJSONArray("comments").getJSONObject(0).getString("id");

        // The resolve toggle must likewise return the full document so the pane survives a resolve.
        WebResponse resolveResp = postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/cc/resolveComment",
                "{\"commentId\":\"" + commentId + "\",\"resolved\":true}");
        assertEquals(200, resolveResp.getStatusCode());
        JSONObject resolved = json(resolveResp);
        assertEquals("# Title\n\nBody line.", resolved.getString("content"), "resolve response must carry content");
        assertTrue(resolved.has("renderedHtml"), "resolve response must carry renderedHtml");
        assertTrue(
                resolved.getJSONArray("comments").getJSONObject(0).getBoolean("resolved"),
                "the comment must now be marked resolved");
    }

    @Test
    void replyThreadsUnderParentWithLabelButKeepsRealAuditAuthor(JenkinsRule j) throws Exception {
        secure(j);
        seed("rp", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);

        // A reviewer leaves a root comment on line 3.
        JSONObject afterRoot = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rp/comments",
                "{\"body\":\"tighten this\",\"line\":3}"));
        String rootId = afterRoot.getJSONArray("comments").getJSONObject(0).getString("id");

        // An automation replies to that comment with a display label. The audit author must stay the real,
        // server-set Jenkins identity (builder) — the client-supplied label only changes what is displayed.
        JSONObject afterReply = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/rp/comments",
                "{\"body\":\"done\",\"line\":3,\"parentId\":\"" + rootId + "\",\"authorLabel\":\"AI response\"}"));
        JSONObject reply = findReply(afterReply.getJSONArray("comments"));
        assertEquals(rootId, reply.getString("parentId"), "the reply must be threaded under the root comment");
        assertEquals("AI response", reply.getString("authorLabel"), "the display label must be preserved");
        assertEquals("builder", reply.getString("author"), "the audit author is the real identity, never the label");
    }

    @Test
    void automatedReplyUsesConfiguredGlobalLabelAndExplicitLabelWins(JenkinsRule j) throws Exception {
        secure(j);
        seed("au", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        cfg.setAutomationReplyName("JENKINS response");
        cfg.save();

        JSONObject afterRoot = json(postJsonResponse(
                j.createWebClient().login("builder"), j, BASE + "views/au/comments", "{\"body\":\"root\",\"line\":3}"));
        String rootId = afterRoot.getJSONArray("comments").getJSONObject(0).getString("id");

        // automated:true with no explicit label -> the configured global default is applied server-side.
        JSONObject afterAuto = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/au/comments",
                "{\"body\":\"auto\",\"line\":3,\"parentId\":\"" + rootId + "\",\"automated\":true}"));
        assertEquals(
                "JENKINS response",
                findReply(afterAuto.getJSONArray("comments")).getString("authorLabel"),
                "an automated reply with no explicit label uses the configured global default");

        // An explicit authorLabel overrides the global default even when automated:true.
        JSONObject afterOverride = json(postJsonResponse(
                j.createWebClient().login("builder"),
                j,
                BASE + "views/au/comments",
                "{\"body\":\"custom\",\"line\":3,\"parentId\":\"" + rootId
                        + "\",\"automated\":true,\"authorLabel\":\"Custom bot\"}"));
        boolean sawCustom = false;
        JSONArray comments = afterOverride.getJSONArray("comments");
        for (int i = 0; i < comments.size(); i++) {
            JSONObject c = comments.getJSONObject(i);
            if (c.has("authorLabel") && "Custom bot".equals(c.getString("authorLabel"))) {
                sawCustom = true;
            }
        }
        assertTrue(sawCustom, "an explicit authorLabel must override the global default");
    }

    @Test
    void replyToAMissingParentIsRejectedWith400(JenkinsRule j) throws Exception {
        secure(j);
        seed("bad", ReviewDocument.FORMAT_MARKDOWN, "# Title\n\nBody line.", true, false);

        // A parentId that does not reference an existing comment is a client error (400), not a 500/404.
        assertEquals(
                400,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/bad/comments",
                        "{\"body\":\"reply\",\"line\":3,\"parentId\":\"no-such-comment\"}"));
    }

    @Test
    void markdownRenderedHtmlCarriesSourceLineAnchors(JenkinsRule j) throws Exception {
        secure(j);
        seed("md", ReviewDocument.FORMAT_MARKDOWN, "# Heading\n\nA paragraph.", true, false);

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/md"));
        String html = detail.getString("renderedHtml");
        // Each top-level block is tagged with its 1-based source line so the client can anchor inline
        // comments on the RENDERED view too (line 1 = heading, line 3 = paragraph after the blank line).
        assertTrue(html.contains("data-source-line=\"1\""), "heading must anchor to source line 1: " + html);
        assertTrue(html.contains("data-source-line=\"3\""), "paragraph must anchor to source line 3: " + html);
    }

    @Test
    void markdownTablesRenderToHtmlTableEndToEnd(JenkinsRule j) throws Exception {
        secure(j);
        // The exact class of content that regressed in the live Interactive View: a GFM pipe table in a
        // markdown review must reach the client as an HTML <table>, not a literal "|"-delimited paragraph.
        seed(
                "tbl",
                ReviewDocument.FORMAT_MARKDOWN,
                "# Report\n\n| Aspect | Value |\n|---|---|\n| length | 1..255 |",
                true,
                false);

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/tbl"));
        String html = detail.getString("renderedHtml");
        // The document body uses the source-line renderer, so the table opens as <table data-source-line=..>.
        assertTrue(html.contains("<table"), "a GFM pipe table must render as an HTML table: " + html);
        assertTrue(html.contains("<th>Aspect</th>"), "table header cells must render: " + html);
        assertFalse(html.contains("|---|"), "the delimiter row must not leak through as literal text: " + html);
    }

    @Test
    void htmlAndCodeContentIsReturnedRawAndNeverServerRendered(JenkinsRule j) throws Exception {
        secure(j);
        String payload = "<script>alert(1)</script>";
        seed("h", ReviewDocument.FORMAT_HTML, payload, true, false);

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/h"));
        // Content is returned verbatim (the client shows it ESCAPED); the server must not pre-render it.
        assertEquals(payload, detail.getString("content"));
        assertFalse(detail.has("renderedHtml"), "HTML/code content must never be server-rendered to HTML");
    }

    @Test
    void markdownAndCommentBodiesAreSanitised(JenkinsRule j) throws Exception {
        secure(j);
        seed("m", ReviewDocument.FORMAT_MARKDOWN, "<script>alert(1)</script>\n\n[x](javascript:alert(1))", true, false);

        JSONObject detail = json(get(j.createWebClient().login("builder"), j, BASE + "views/m"));
        assertTrue(detail.has("renderedHtml"), "markdown is rendered to sanitised HTML");
        String html = detail.getString("renderedHtml");
        assertFalse(html.contains("<script>"), "raw <script> must be escaped: " + html);
        assertFalse(html.contains("javascript:"), "javascript: scheme must be sanitised: " + html);

        // Comment bodies are untrusted markdown too and must be sanitised in bodyHtml.
        assertEquals(
                200,
                postJson(
                        j.createWebClient().login("builder"),
                        j,
                        BASE + "views/m/comments",
                        "{\"body\":\"<script>bad</script>\\n\\n[l](javascript:alert(1))\"}"));
        JSONObject after = json(get(j.createWebClient().login("builder"), j, BASE + "views/m"));
        String bodyHtml = after.getJSONArray("comments").getJSONObject(0).getString("bodyHtml");
        assertFalse(bodyHtml.contains("<script>"), "comment <script> must be escaped: " + bodyHtml);
        assertFalse(bodyHtml.contains("javascript:"), "comment javascript: must be sanitised: " + bodyHtml);
    }

    // ---- helpers ----

    private static void secure(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.READ).everywhere().to("reader", "builder", "admin");
        auth.grant(Item.READ).everywhere().to("reader", "builder");
        auth.grant(Item.BUILD).everywhere().to("builder");
        auth.grant(Jenkins.ADMINISTER).everywhere().to("admin");
        j.jenkins.setAuthorizationStrategy(auth);
        j.createFreeStyleProject(JOB);
    }

    private static ReviewDocument seed(
            String id, String format, String content, boolean commentable, boolean editable) {
        ReviewDocument doc = new ReviewDocument(
                id,
                JOB,
                1,
                "Report",
                "Title",
                "file",
                format,
                "text",
                "tester",
                System.currentTimeMillis(),
                commentable,
                editable,
                true,
                false,
                null,
                0L,
                null,
                null);
        return ViewStore.get().submit(doc, content);
    }

    private static WebResponse get(JenkinsRule.WebClient wc, JenkinsRule j, String path) throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(true);
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET))
                .getWebResponse();
    }

    private static int postJson(JenkinsRule.WebClient wc, JenkinsRule j, String path, String body) throws Exception {
        return postJsonResponse(wc, j, path, body).getStatusCode();
    }

    private static WebResponse postJsonResponse(JenkinsRule.WebClient wc, JenkinsRule j, String path, String body)
            throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false);
        WebRequest crumbReq = new WebRequest(new URL(j.getURL(), "crumbIssuer/api/json"), HttpMethod.GET);
        JSONObject crumb =
                JSONObject.fromObject(wc.getPage(crumbReq).getWebResponse().getContentAsString());
        WebRequest req = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        req.setAdditionalHeader(crumb.getString("crumbRequestField"), crumb.getString("crumb"));
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(body);
        return wc.getPage(req).getWebResponse();
    }

    private static int postNoCrumb(JenkinsRule.WebClient wc, JenkinsRule j, String path, String body) throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false);
        WebRequest req = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(body);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    private static JSONObject json(WebResponse r) {
        return JSONObject.fromObject(r.getContentAsString());
    }

    /** Return the first comment in the array that is a threaded reply (carries a {@code parentId}). */
    private static JSONObject findReply(JSONArray comments) {
        for (int i = 0; i < comments.size(); i++) {
            JSONObject c = comments.getJSONObject(i);
            if (c.has("parentId")) {
                return c;
            }
        }
        throw new AssertionError("no threaded reply found in comments: " + comments);
    }
}

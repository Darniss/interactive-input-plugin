package io.jenkins.plugins.interactiveinput.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.model.Item;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.model.QuestionStatus;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.net.URL;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * REST API tests (§6.3, §7.3): happy paths, the {@code Overall/Read} + {@code Item.BUILD} permission
 * matrix, existence-hiding on detail, feature-flag gating, and a CSRF-crumbed answer POST.
 */
@WithJenkins
class RestApiTest {

    private static final String JOB = "job-a";
    private static final String BASE = "interactive-input/api/v1/";

    @Test
    void healthIsAnonymous(JenkinsRule j) throws Exception {
        secure(j);
        WebResponse r = get(j.createWebClient(), j, BASE + "health");
        assertEquals(200, r.getStatusCode());
        assertEquals("ok", json(r).getString("status"));
    }

    @Test
    void disabledApiReturns404(JenkinsRule j) throws Exception {
        secure(j);
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        cfg.getFeatures().setRestApi(false);
        cfg.save();
        WebResponse r = get(j.createWebClient(), j, BASE + "health");
        assertEquals(404, r.getStatusCode());
    }

    @Test
    void listRequiresOverallReadAndFiltersByAnswerable(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");

        assertEquals(403, get(j.createWebClient(), j, BASE + "questions").getStatusCode());

        WebResponse readerResp = get(j.createWebClient().login("reader"), j, BASE + "questions");
        assertEquals(200, readerResp.getStatusCode());
        assertEquals(0, json(readerResp).getInt("count"), "reader cannot answer, so lists none");

        WebResponse builderResp = get(j.createWebClient().login("builder"), j, BASE + "questions");
        assertEquals(200, builderResp.getStatusCode());
        assertEquals(1, json(builderResp).getInt("count"));
    }

    @Test
    void allParamRequiresAdminister(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");
        assertEquals(403, get(j.createWebClient().login("reader"), j, BASE + "questions?all=true").getStatusCode());
        assertEquals(200, get(j.createWebClient().login("admin"), j, BASE + "questions?all=true").getStatusCode());
    }

    @Test
    void detailHiddenWithoutItemRead(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");
        WebResponse ok = get(j.createWebClient().login("builder"), j, BASE + "questions/q1");
        assertEquals(200, ok.getStatusCode());
        assertEquals("q1", json(ok).getString("id"));
        // Anonymous lacks Item.READ -> 404 (never reveal existence).
        assertEquals(404, get(j.createWebClient(), j, BASE + "questions/q1").getStatusCode());
    }

    @Test
    void answerRequiresBuildPermissionThenResumes(JenkinsRule j) throws Exception {
        secure(j);
        submit("q1");

        assertEquals(403, postJson(j.createWebClient().login("reader"), j, BASE + "questions/q1/answer", "{\"choiceId\":\"yes\"}"));

        // Unknown choice -> 400 validation error.
        assertEquals(400, postJson(j.createWebClient().login("builder"), j, BASE + "questions/q1/answer", "{\"choiceId\":\"nope\"}"));

        assertEquals(200, postJson(j.createWebClient().login("builder"), j, BASE + "questions/q1/answer", "{\"choiceId\":\"yes\"}"));
        assertEquals(QuestionStatus.ANSWERED, QuestionStore.get().get("q1").getStatus());

        // Second answer on a settled question -> 409.
        assertEquals(409, postJson(j.createWebClient().login("builder"), j, BASE + "questions/q1/answer", "{\"choiceId\":\"yes\"}"));
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

    private static void submit(String id) {
        QuestionStore.get()
                .submit(new Question(
                        id, "Approve?", List.of(new Choice("yes", "Yes")), false, 0L, null, null, JOB, 1,
                        System.currentTimeMillis(), false));
    }

    private static WebResponse get(JenkinsRule.WebClient wc, JenkinsRule j, String path) throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        // Follow redirects like a real HTTP client: collection nodes emit a trailing-slash 302.
        wc.getOptions().setRedirectEnabled(true);
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET)).getWebResponse();
    }

    private static int postJson(JenkinsRule.WebClient wc, JenkinsRule j, String path, String body) throws Exception {
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false);
        WebRequest crumbReq = new WebRequest(new URL(j.getURL(), "crumbIssuer/api/json"), HttpMethod.GET);
        JSONObject crumb = JSONObject.fromObject(wc.getPage(crumbReq).getWebResponse().getContentAsString());
        WebRequest req = new WebRequest(new URL(j.getURL(), path), HttpMethod.POST);
        req.setAdditionalHeader(crumb.getString("crumbRequestField"), crumb.getString("crumb"));
        req.setAdditionalHeader("Content-Type", "application/json");
        req.setRequestBody(body);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    private static JSONObject json(WebResponse r) {
        return JSONObject.fromObject(r.getContentAsString());
    }
}

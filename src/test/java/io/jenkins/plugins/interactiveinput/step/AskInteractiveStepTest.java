package io.jenkins.plugins.interactiveinput.step;

import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.Result;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * End-to-end tests for the {@code askInteractive} step (§6.1): the pipeline must pause, resume with a
 * choice / free-text answer, fail cleanly on abort, and time out on SLA expiry.
 */
@WithJenkins
class AskInteractiveStepTest {

    @Test
    void choiceAnswerResumesWithChoiceId(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j,
                "choice",
                "def r = askInteractive(prompt: 'Pick env', choices: ["
                        + "[id: 'staging', label: 'Staging', why: 'matches'],"
                        + "[id: 'production', label: 'Production']])\n"
                        + "echo \"ANS=${r}\"");

        Question q = awaitOneWaiting(j);
        QuestionStore.get().answer(q.getId(), "production", null, "alice", QuestionStore.SOURCE_UI);

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("ANS=production", b);
    }

    @Test
    void freeTextAnswerResumesWithMap(JenkinsRule j) throws Exception {
        WorkflowRun b = start(
                j, "freetext", "def r = askInteractive(prompt: 'Notes?', allowFreeText: true)\necho \"ANS=${r}\"");

        Question q = awaitOneWaiting(j);
        QuestionStore.get().answer(q.getId(), null, "ship it", "bob", QuestionStore.SOURCE_UI);

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("text:ship it", b);
    }

    @Test
    void abortFailsBuildWithAbortException(JenkinsRule j) throws Exception {
        WorkflowRun b = start(j, "abort", "askInteractive(prompt: 'Proceed?')\necho 'unreached'");

        Question q = awaitOneWaiting(j);
        QuestionStore.get().abort(q.getId(), "carol", QuestionStore.SOURCE_UI);

        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("aborted by carol", b);
        j.assertLogNotContains("unreached", b);
    }

    @Test
    void slaExpiryTimesOutTheBuild(JenkinsRule j) throws Exception {
        WorkflowRun b = start(j, "sla", "askInteractive(prompt: 'Deploy?', slaMinutes: 1)\necho 'unreached'");

        Question q = awaitOneWaiting(j);
        // Force the SLA clock past the deadline; the ticker's expiry path runs synchronously here.
        QuestionStore.get().expireOverdue(System.currentTimeMillis() + 61_000L);

        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("SLA elapsed", b);
    }

    // ---- helpers ----

    private static WorkflowRun start(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, name);
        p.setDefinition(new CpsFlowDefinition(script, true));
        return p.scheduleBuild2(0).waitForStart();
    }

    private static Question awaitOneWaiting(JenkinsRule j) throws InterruptedException {
        QuestionStore store = QuestionStore.get();
        for (int i = 0; i < 100; i++) {
            if (store.listAll().size() == 1) {
                return store.listAll().get(0);
            }
            Thread.sleep(100L);
        }
        fail("no WAITING question appeared in the store");
        throw new AssertionError("unreachable");
    }
}

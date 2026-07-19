package io.jenkins.plugins.interactiveinput.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.List;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression guard for the multi-question "series" modal discarding a half-typed answer when the user
 * pages to another question and back.
 *
 * <p>Root cause: the pager re-fetched each question's detail and rebuilt the form from scratch on every
 * navigation, so the free-text {@code <textarea>} came back empty and any unsubmitted text was lost.
 * The fix snapshots per-question drafts before navigating and restores them, rendering each slide from
 * the already-fetched list item instead of re-fetching.
 *
 * <p>Drives the real UI in HtmlUnit: open the bell, click "Answer all", type into slide 1, page to
 * slide 2 and back, and assert the draft survived.
 */
@WithJenkins
class SeriesDraftRetentionTest {

    private static final String JOB = "series-job";
    private static final String DRAFT = "canary 10 -> 50 -> 100, rollback on 5xx";

    @Test
    void freeTextDraftSurvivesPagingBetweenSeriesQuestions(JenkinsRule j) throws Exception {
        InteractiveInputAppearanceConfig cfg = InteractiveInputAppearanceConfig.get();
        assertNotNull(cfg, "appearance config must be registered");
        cfg.setNotificationCentre(true); // the global bell is off by default

        j.createFreeStyleProject(JOB);
        QuestionStore store = QuestionStore.get();
        long now = System.currentTimeMillis();
        // Two free-text questions (no starter => visible to everyone) so any slide shows a textarea and
        // "Answer all (2)" offers the series pager.
        store.submit(new Question("s1", "Deploy note (free text)?", List.of(), true, 0L, null, null, JOB, 1, null, now, false));
        store.submit(new Question("s2", "Rollback note (free text)?", List.of(), true, 0L, null, null, JOB, 1, null, now, false));

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setJavaScriptEnabled(true);
            wc.getOptions().setThrowExceptionOnScriptError(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

            HtmlPage page = wc.goTo("");
            wc.waitForBackgroundJavaScript(3000); // let the bell's first poll populate its cache

            ((HtmlElement) page.querySelector(".ii-bell-btn")).click();
            HtmlElement answerAll = (HtmlElement) page.querySelector(".ii-answer-all");
            assertNotNull(answerAll, "the bell must offer 'Answer all' for two waiting questions");
            answerAll.click();
            wc.waitForBackgroundJavaScript(2000);

            HtmlTextArea slide1 = (HtmlTextArea) page.querySelector(".ii-modal .ii-freetext textarea");
            assertNotNull(slide1, "the first series slide must show a free-text field");
            slide1.setText(DRAFT);

            // Page forward to slide 2, then back to slide 1.
            ((HtmlElement) page.querySelector(".ii-series-next")).click();
            wc.waitForBackgroundJavaScript(1000);
            ((HtmlElement) page.querySelector(".ii-series-prev")).click();
            wc.waitForBackgroundJavaScript(1000);

            HtmlTextArea slide1Again = (HtmlTextArea) page.querySelector(".ii-modal .ii-freetext textarea");
            assertNotNull(slide1Again, "the first slide must render again after paging back");
            assertEquals(DRAFT, slide1Again.getText(), "the typed draft must survive paging away and back");
        }
    }
}

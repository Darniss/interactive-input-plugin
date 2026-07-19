package io.jenkins.plugins.interactiveinput.step;

import hudson.AbortException;
import hudson.console.HyperlinkNote;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.interactiveinput.model.Answer;
import io.jenkins.plugins.interactiveinput.model.Choice;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import io.jenkins.plugins.interactiveinput.ui.InteractiveInputRunAction;
import io.jenkins.plugins.interactiveinput.util.CauseResolver;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/**
 * Durable, restart-safe execution for {@link AskInteractiveStep}.
 *
 * <p>Mirrors {@code pipeline-input-step}'s {@code InputStepExecution} (verified against release
 * 560.v56198a_642157): {@link #start()} returns {@code false} so the CPS thread is released and the
 * pipeline is genuinely paused; the {@link QuestionStore} resumes the pipeline via
 * {@link StepContext#onSuccess}/{@link StepContext#onFailure} when a human answers.
 *
 * <p>For auditability it also mirrors the built-in {@code input} step's console UX: it logs an
 * anchored console link to the per-build audit page and marks the flow node "paused" (so stage/flow
 * views show a pause), then logs the resolved outcome (who answered/aborted, and what was chosen)
 * when the question settles.
 */
public class AskInteractiveStepExecution extends AbstractStepExecutionImpl {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(AskInteractiveStepExecution.class.getName());

    /** Cap free-text echoed to the build log so a long answer cannot flood the console. */
    private static final int MAX_LOGGED_ANSWER = 140;

    private static final String LOG_PREFIX = "[interactive-input] ";

    private final AskInteractiveStep step;

    /** Assigned in {@link #start()}; persisted so the question can be re-attached after a restart. */
    private String questionId;

    AskInteractiveStepExecution(StepContext context, AskInteractiveStep step) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        StepContext ctx = getContext();
        Run<?, ?> run = ctx.get(Run.class);
        TaskListener listener = ctx.get(TaskListener.class);

        this.questionId = UUID.randomUUID().toString();
        Question question = new Question(
                questionId,
                step.getPrompt(),
                step.getChoices(),
                step.isAllowFreeText(),
                step.resolveSlaMs(),
                step.getContextMarkdown(),
                step.getSubmitterFilter(),
                run.getParent().getFullName(),
                run.getNumber(),
                CauseResolver.startedBy(run),
                System.currentTimeMillis(),
                false);

        QuestionStore store = QuestionStore.get();
        store.submit(question);
        store.register(questionId, this::onResolved);

        if (listener != null) {
            // Anchored link to the per-build audit page, like the built-in input step. The leading
            // "/" is resolved against the context path by HyperlinkNote (verified against core).
            String link = HyperlinkNote.encodeTo(
                    "/" + run.getUrl() + InteractiveInputRunAction.URL_NAME + "/", "Open interactive input");
            listener.getLogger().println(LOG_PREFIX + step.getPrompt() + " — waiting for a human answer. " + link);
        }
        markPaused(ctx);
        return false; // asynchronous: the pipeline pauses here
    }

    /** Invoked by the store exactly once when the question reaches a terminal state. */
    private void onResolved(Question q) {
        StepContext ctx = getContext();
        endPause(ctx);
        logOutcome(ctx, q);
        switch (q.getStatus()) {
            case ANSWERED:
                Answer a = q.getAnswer();
                ctx.onSuccess(a != null ? a.toStepReturnValue() : null);
                break;
            case ABORTED:
                String by = q.getAnswer() != null ? q.getAnswer().getAnsweredBy() : "unknown";
                ctx.onFailure(new AbortException("askInteractive aborted by " + by));
                break;
            case EXPIRED:
                ctx.onFailure(new TimeoutException(
                        "askInteractive SLA elapsed with no answer after " + (step.resolveSlaMs() / 60000L) + " min"));
                break;
            default:
                LOGGER.warning("onResolved called for non-terminal question " + q.getId());
                break;
        }
    }

    /** Add a {@link PauseAction} to the current flow node so stage/flow views show "Paused". */
    private void markPaused(StepContext ctx) {
        try {
            FlowNode node = ctx.get(FlowNode.class);
            if (node != null) {
                node.addAction(new PauseAction("Interactive input"));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.FINE, e, () -> "could not mark flow node paused for question " + questionId);
        }
    }

    /** End the pause started in {@link #markPaused} so the flow-graph pause duration is recorded. */
    private void endPause(StepContext ctx) {
        try {
            FlowNode node = ctx.get(FlowNode.class);
            if (node != null && PauseAction.isPaused(node)) {
                PauseAction.endCurrentPause(node);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.FINE, e, () -> "could not end pause for question " + questionId);
        }
    }

    /** Log a human-readable audit line (who answered/aborted and what was chosen) to the build log. */
    private void logOutcome(StepContext ctx, Question q) {
        try {
            TaskListener l = ctx.get(TaskListener.class);
            if (l != null) {
                l.getLogger().println(LOG_PREFIX + describeOutcome(q));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.FINE, e, () -> "could not log outcome for question " + questionId);
        }
    }

    private String describeOutcome(Question q) {
        Answer a = q.getAnswer();
        switch (q.getStatus()) {
            case ANSWERED:
                String by = a != null ? a.getAnsweredBy() : "unknown";
                if (a != null && a.isDeny()) {
                    return "Denied by " + by;
                }
                if (a != null && a.getChoiceId() != null) {
                    return "Answered by " + by + ": " + choiceLabel(a.getChoiceId());
                }
                String ft = a != null ? a.getFreeText() : null;
                return "Answered by " + by + ": " + abbreviate(ft);
            case ABORTED:
                return "Aborted by " + (a != null ? a.getAnsweredBy() : "unknown");
            case EXPIRED:
                return "Expired: SLA elapsed with no answer";
            default:
                return "Resolved: " + q.getStatus();
        }
    }

    /** @return the human label for a chosen id, falling back to the id itself. */
    private String choiceLabel(String choiceId) {
        if (step.getChoices() != null) {
            for (Choice c : step.getChoices()) {
                if (c.getId().equals(choiceId)) {
                    return c.getLabel();
                }
            }
        }
        return choiceId;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_LOGGED_ANSWER ? oneLine : oneLine.substring(0, MAX_LOGGED_ANSWER) + "…";
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        // The framework will deliver the cause via super.stop(); mark the question aborted for bell
        // cleanup without double-resolving the context.
        if (questionId != null) {
            QuestionStore store = QuestionStore.get();
            store.unregister(questionId);
            store.abortForShutdown(questionId, QuestionStore.SOURCE_SYSTEM);
        }
        super.stop(cause);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (questionId != null) {
            // Re-attach after a Jenkins restart. If already answered while we were away, register()
            // resolves immediately.
            QuestionStore.get().register(questionId, this::onResolved);
        }
    }

    public String getQuestionId() {
        return questionId;
    }
}

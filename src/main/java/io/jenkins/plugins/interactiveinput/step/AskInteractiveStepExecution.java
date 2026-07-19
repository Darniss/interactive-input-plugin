package io.jenkins.plugins.interactiveinput.step;

import hudson.AbortException;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.interactiveinput.model.Answer;
import io.jenkins.plugins.interactiveinput.model.Question;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/**
 * Durable, restart-safe execution for {@link AskInteractiveStep}.
 *
 * <p>Mirrors {@code pipeline-input-step}'s {@code InputStepExecution} (verified against release
 * 560.v56198a_642157): {@link #start()} returns {@code false} so the CPS thread is released and the
 * pipeline is genuinely paused; the {@link QuestionStore} resumes the pipeline via
 * {@link StepContext#onSuccess}/{@link StepContext#onFailure} when a human answers.
 */
public class AskInteractiveStepExecution extends AbstractStepExecutionImpl {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(AskInteractiveStepExecution.class.getName());

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
                System.currentTimeMillis(),
                false);

        QuestionStore store = QuestionStore.get();
        store.submit(question);
        store.register(questionId, this::onResolved);

        if (listener != null) {
            listener.getLogger().printf(
                    "[interactive-input] Waiting for a human answer: %s (id=%s)%n", step.getPrompt(), questionId);
        }
        return false; // asynchronous: the pipeline pauses here
    }

    /** Invoked by the store exactly once when the question reaches a terminal state. */
    private void onResolved(Question q) {
        StepContext ctx = getContext();
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

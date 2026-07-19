package io.jenkins.plugins.interactiveinput.step;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.model.Choice;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * The {@code askInteractive} pipeline step (§6.1): a durable, human-answered input step that surfaces
 * in the notification bell and rich modal.
 *
 * <pre>{@code
 * def answer = askInteractive(
 *     prompt: 'Which environment should I deploy to?',
 *     choices: [
 *         [id: 'staging',    label: 'Staging',    why: 'Latest passing build hash matches.'],
 *         [id: 'production', label: 'Production', why: 'PR was tagged release/*.']
 *     ],
 *     allowFreeText: false,
 *     slaMinutes: 15,
 *     submitterFilter: null,
 *     contextMarkdown: null)
 * }</pre>
 *
 * <p>Return value: the picked choice id ({@code String}); or {@code [text: '...', choice: null]} for
 * free text; a Groovy {@code AbortException} on abort; a {@code TimeoutException} on SLA expiry.
 */
public class AskInteractiveStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    @NonNull
    private final String prompt;

    @NonNull
    private List<Choice> choices = new ArrayList<>();

    private boolean allowFreeText;

    private int slaMinutes = -1; // -1 => fall back to the JCasC default SLA

    @CheckForNull
    private String submitterFilter;

    @CheckForNull
    private String contextMarkdown;

    /** Reserved for v0.2 (Slack/email/PagerDuty escalation). Accepted but ignored in v0.1 (§6.1). */
    @CheckForNull
    private String escalation;

    @DataBoundConstructor
    public AskInteractiveStep(@NonNull String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        this.prompt = prompt;
    }

    @NonNull
    public String getPrompt() {
        return prompt;
    }

    @NonNull
    public List<Choice> getChoices() {
        return Collections.unmodifiableList(choices);
    }

    @DataBoundSetter
    public void setChoices(@CheckForNull List<Choice> choices) {
        this.choices = choices == null ? new ArrayList<>() : new ArrayList<>(choices);
    }

    public boolean isAllowFreeText() {
        return allowFreeText;
    }

    @DataBoundSetter
    public void setAllowFreeText(boolean allowFreeText) {
        this.allowFreeText = allowFreeText;
    }

    public int getSlaMinutes() {
        return slaMinutes;
    }

    @DataBoundSetter
    public void setSlaMinutes(int slaMinutes) {
        this.slaMinutes = slaMinutes;
    }

    @CheckForNull
    public String getSubmitterFilter() {
        return submitterFilter;
    }

    @DataBoundSetter
    public void setSubmitterFilter(@CheckForNull String submitterFilter) {
        this.submitterFilter = submitterFilter;
    }

    @CheckForNull
    public String getContextMarkdown() {
        return contextMarkdown;
    }

    @DataBoundSetter
    public void setContextMarkdown(@CheckForNull String contextMarkdown) {
        this.contextMarkdown = contextMarkdown;
    }

    @CheckForNull
    public String getEscalation() {
        return escalation;
    }

    @DataBoundSetter
    public void setEscalation(@CheckForNull String escalation) {
        this.escalation = escalation;
    }

    /** @return the effective SLA in milliseconds, resolving the JCasC default when unset. */
    public long resolveSlaMs() {
        int minutes = slaMinutes >= 0 ? slaMinutes : InteractiveInputGlobalConfig.defaultSlaMinutesOrDefault();
        return minutes > 0 ? (long) minutes * 60_000L : 0L;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new AskInteractiveStepExecution(context, this);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "askInteractive";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Ask an interactive human-in-the-loop question";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, FlowNode.class);
        }
    }
}

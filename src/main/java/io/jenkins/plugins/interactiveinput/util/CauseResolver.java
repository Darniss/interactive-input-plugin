package io.jenkins.plugins.interactiveinput.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Cause;
import hudson.model.Run;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;

/**
 * Resolves a short, human-readable "who/what started this build" label for a {@link Run}, used to
 * attribute interactive-input questions in the notification surfaces ("started by &lt;user&gt;").
 *
 * <p>Prefers a real Jenkins user id ({@link Cause.UserIdCause}); otherwise falls back to a stable
 * label for common non-interactive triggers (SCM, timer, upstream) or {@code "system"}.
 */
public final class CauseResolver {

    /** Returned when a build has no attributable cause. */
    public static final String SYSTEM = "system";

    private CauseResolver() {}

    /**
     * @param run the build to attribute (must not be {@code null})
     * @return the triggering user id, or a trigger label ({@code scm}, {@code timer}, {@code upstream}),
     *     or {@link #SYSTEM}. Never {@code null} or empty.
     */
    @NonNull
    public static String startedBy(@NonNull Run<?, ?> run) {
        Cause.UserIdCause user = run.getCause(Cause.UserIdCause.class);
        if (user != null) {
            String id = user.getUserId();
            return (id != null && !id.trim().isEmpty()) ? id : "unknown";
        }
        if (run.getCause(SCMTrigger.SCMTriggerCause.class) != null) {
            return "scm";
        }
        if (run.getCause(TimerTrigger.TimerTriggerCause.class) != null) {
            return "timer";
        }
        if (run.getCause(Cause.UpstreamCause.class) != null) {
            return "upstream";
        }
        return SYSTEM;
    }
}

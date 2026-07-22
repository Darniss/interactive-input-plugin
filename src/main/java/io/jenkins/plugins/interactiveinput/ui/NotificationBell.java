package io.jenkins.plugins.interactiveinput.ui;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.PageDecorator;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputAppearanceConfig;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Injects the notification bell into every Jenkins page (§6.2.1, §8.4).
 *
 * <p>Implemented as a {@link PageDecorator} whose {@code footer.jelly} adds a small bell element plus
 * the {@code interactive-input.bell} adjunct (vanilla JS + CSS — no framework, §8.10). The JS polls
 * the REST endpoint for the pending count and renders the dropdown and modal client-side.
 *
 * <p>The bell is context-aware: on the dashboard it lists every question the viewer can answer;
 * inside a pipeline (a page under a {@link Job}) it scopes to that pipeline's questions. The current
 * job is resolved from the Stapler ancestor chain and handed to the client via a {@code data-job}
 * attribute; the initial server-rendered count is scoped to match.
 */
@Extension
public class NotificationBell extends PageDecorator {

    private static final Logger LOGGER = Logger.getLogger(NotificationBell.class.getName());

    /**
     * @return {@code true} if the bell should render on the current page: the feature is enabled and
     *     the viewer has at least Overall/Read (so it never shows on the login page to anonymous).
     */
    public boolean isBellVisible() {
        if (!InteractiveInputAppearanceConfig.notificationCentreEnabled()) {
            return false;
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        return j != null && j.hasPermission(Jenkins.READ);
    }

    /**
     * @return {@code true} if the shared client adjunct should load on this page: the viewer has at
     *     least Overall/Read. The adjunct is loaded even when the bell mount itself is hidden (the
     *     notification centre is off) so its delegated click handlers work on every page — the
     *     build-history badge and, for B6, the console "Open interactive input" link, which opens the
     *     dialog <em>in place</em> on the Console Output page. Without a mount the adjunct only wires
     *     those handlers; it never polls. It stays gated on Overall/Read so it never loads for
     *     anonymous users on the login page.
     */
    public boolean isClientVisible() {
        Jenkins j = Jenkins.getInstanceOrNull();
        return j != null && j.hasPermission(Jenkins.READ);
    }

    /**
     * @return the full name of the {@link Job} the current request is under, or an empty string on
     *     the dashboard / non-job pages. Drives the client's dashboard-vs-pipeline scoping.
     */
    public String getCurrentJobFullName() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) {
            return "";
        }
        Job<?, ?> job = req.findAncestorObject(Job.class);
        return job != null ? job.getFullName() : "";
    }

    /** @return the pending count for the initial server render, scoped to the current job if any. */
    public int getInitialCount() {
        try {
            QuestionStore store = QuestionStore.get();
            String jobFullName = getCurrentJobFullName();
            return jobFullName.isEmpty()
                    ? store.countNotifications()
                    : store.countNotificationsForJob(jobFullName);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "could not compute initial bell count", e);
            return 0;
        }
    }

    public int getPollingIntervalSeconds() {
        return InteractiveInputGlobalConfig.pollingIntervalSecondsOrDefault();
    }

    public boolean isRichModalEnabled() {
        return InteractiveInputGlobalConfig.featuresOrDefault().isRichModal();
    }

    /** @return the theme-aware symbol class for the configured notification icon. */
    public String getIconClassName() {
        return InteractiveInputAppearanceConfig.iconClassNameOrDefault();
    }

    /**
     * @return whether the client should mirror the pending count in the browser tab (title prefix +
     *     a dot painted on top of the existing favicon). On by default; a look-and-feel toggle under
     *     <em>Manage Jenkins → Appearance</em>. The client reads this from the bell mount's
     *     {@code data-tab-badge} attribute.
     */
    public boolean isTabNotificationBadge() {
        return InteractiveInputAppearanceConfig.tabNotificationBadgeEnabled();
    }
}

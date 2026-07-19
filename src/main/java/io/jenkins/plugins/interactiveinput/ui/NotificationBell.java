package io.jenkins.plugins.interactiveinput.ui;

import hudson.Extension;
import hudson.model.PageDecorator;
import io.jenkins.plugins.interactiveinput.config.InteractiveInputGlobalConfig;
import io.jenkins.plugins.interactiveinput.store.QuestionStore;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Injects the notification bell into every Jenkins page (§6.2.1, §8.4).
 *
 * <p>Implemented as a {@link PageDecorator} whose {@code footer.jelly} adds a small bell element plus
 * the {@code interactive-input.bell} adjunct (vanilla JS + CSS — no framework, §8.10). The JS polls
 * the REST endpoint for the pending count and renders the dropdown and modal client-side.
 */
@Extension
public class NotificationBell extends PageDecorator {

    private static final Logger LOGGER = Logger.getLogger(NotificationBell.class.getName());

    /**
     * @return {@code true} if the bell should render on the current page: the feature is enabled and
     *     the viewer has at least Overall/Read (so it never shows on the login page to anonymous).
     */
    public boolean isBellVisible() {
        if (!InteractiveInputGlobalConfig.featuresOrDefault().isNavBarBell()) {
            return false;
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        return j != null && j.hasPermission(Jenkins.READ);
    }

    /** @return the number of pending questions the current user can answer (initial server render). */
    public int getInitialCount() {
        try {
            return QuestionStore.get().countAnswerable();
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
}

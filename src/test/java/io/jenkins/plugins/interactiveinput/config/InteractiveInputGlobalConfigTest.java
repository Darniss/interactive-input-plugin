package io.jenkins.plugins.interactiveinput.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies the functional feature flags round-trip through the <em>Manage Jenkins → System</em> UI —
 * in particular the opt-in input-step bridge toggle — and that saving the form neither loses the
 * checked-by-default flags nor resets settings that are not on the form (polling / SLA / retention).
 */
@WithJenkins
class InteractiveInputGlobalConfigTest {

    @Test
    void inputStepBridgeTogglesThroughTheUiAndPreservesOtherSettings(JenkinsRule j) throws Exception {
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        assertNotNull(cfg, "global config must be registered");
        assertFalse(cfg.getFeatures().isInputStepBridge(), "the bridge is off by default");
        // A non-default retention (as JCasC / Script Console would set) must survive a System save.
        cfg.setRetentionDays(3);

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        HtmlCheckBoxInput bridge = form.getInputByName("_.inputStepBridge");
        bridge.setChecked(true);
        j.submit(form);

        InteractiveInputGlobalConfig after = InteractiveInputGlobalConfig.get();
        assertTrue(after.getFeatures().isInputStepBridge(), "the bridge must be enabled via the UI checkbox");
        assertTrue(after.getFeatures().isAskInteractiveStep(), "checked-by-default flags must stay on");
        assertTrue(after.getFeatures().isRestApi(), "checked-by-default flags must stay on");
        assertEquals(3, after.getRetentionDays(), "configure() must not reset settings that are not on the form");
    }

    @Test
    void uncheckingAFlagTurnsItOff(JenkinsRule j) throws Exception {
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        HtmlCheckBoxInput restApi = form.getInputByName("_.restApi");
        restApi.setChecked(false);
        j.submit(form);

        assertFalse(
                InteractiveInputGlobalConfig.get().getFeatures().isRestApi(),
                "unchecking a flag must turn it off (configure() starts all-off before binding)");
    }

    @Test
    void authorizationSwitchesDefaultOffAndRoundTripThroughTheUi(JenkinsRule j) throws Exception {
        // B18: userScopedNotifications + lockToBuildStarter moved here (System). Both default off.
        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        assertNotNull(cfg);
        assertFalse(cfg.isUserScopedNotifications(), "user-scoped notifications off by default");
        assertFalse(cfg.isLockToBuildStarter(), "lock-to-build-starter off by default");
        assertFalse(InteractiveInputGlobalConfig.userScopedNotificationsEnabled());
        assertFalse(InteractiveInputGlobalConfig.lockToBuildStarterEnabled());

        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");
        form.getInputByName("_.userScopedNotifications").setChecked(true);
        form.getInputByName("_.lockToBuildStarter").setChecked(true);
        j.submit(form);

        assertTrue(InteractiveInputGlobalConfig.userScopedNotificationsEnabled(), "enabled via the System UI");
        assertTrue(InteractiveInputGlobalConfig.lockToBuildStarterEnabled(), "enabled via the System UI");

        // Unchecking turns them back off (configure() starts them off before binding).
        HtmlForm form2 = j.createWebClient().goTo("configure").getFormByName("config");
        form2.getInputByName("_.userScopedNotifications").setChecked(false);
        form2.getInputByName("_.lockToBuildStarter").setChecked(false);
        j.submit(form2);

        assertFalse(InteractiveInputGlobalConfig.userScopedNotificationsEnabled());
        assertFalse(InteractiveInputGlobalConfig.lockToBuildStarterEnabled());
    }
}

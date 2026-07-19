package io.jenkins.plugins.interactiveinput.config;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Per-capability <em>functional</em> feature flags (§6.4), shown under <em>Manage Jenkins →
 * System</em>. Every capability is opt-in-able so an operator can enable or disable it via the UI or
 * JCasC without uninstalling the plugin.
 *
 * <p>Defaults: the step, modal and REST API are on; the input-step bridge and dashboard tile are
 * off. Notification-surface visibility (global bell, per-project centre, job-page box, icon) lives
 * separately in {@link InteractiveInputAppearanceConfig} under <em>Manage Jenkins → Appearance</em>,
 * per Jenkins core guidance to keep look-and-feel settings out of functional configuration.
 */
public class Features extends AbstractDescribableImpl<Features> {

    private boolean askInteractiveStep = true;
    private boolean richModal = true;
    private boolean restApi = true;
    private boolean inputStepBridge = false;
    private boolean dashboardTile = false;

    @DataBoundConstructor
    public Features() {
        // Defaults set via field initialisers; JCasC/Stapler apply overrides through setters.
    }

    public boolean isAskInteractiveStep() {
        return askInteractiveStep;
    }

    @DataBoundSetter
    public void setAskInteractiveStep(boolean askInteractiveStep) {
        this.askInteractiveStep = askInteractiveStep;
    }

    public boolean isRichModal() {
        return richModal;
    }

    @DataBoundSetter
    public void setRichModal(boolean richModal) {
        this.richModal = richModal;
    }

    public boolean isRestApi() {
        return restApi;
    }

    @DataBoundSetter
    public void setRestApi(boolean restApi) {
        this.restApi = restApi;
    }

    public boolean isInputStepBridge() {
        return inputStepBridge;
    }

    @DataBoundSetter
    public void setInputStepBridge(boolean inputStepBridge) {
        this.inputStepBridge = inputStepBridge;
    }

    public boolean isDashboardTile() {
        return dashboardTile;
    }

    @DataBoundSetter
    public void setDashboardTile(boolean dashboardTile) {
        this.dashboardTile = dashboardTile;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Features> {
        @Override
        public String getDisplayName() {
            return "Interactive Input features";
        }
    }
}

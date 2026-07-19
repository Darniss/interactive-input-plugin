package io.jenkins.plugins.interactiveinput.config;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Global, JCasC-compatible configuration for the plugin (§6.4, §8.8).
 *
 * <p>Maps to the JCasC path {@code unclassified.interactiveInput}. Every capability is opt-in via
 * {@link Features}; the bell cadence and default SLA live in {@link Polling} and {@link Sla}.
 */
@Extension
@Symbol("interactiveInput")
public class InteractiveInputGlobalConfig extends GlobalConfiguration {

    /** Default retention for answered/aborted/expired questions before compaction (§8.2). */
    public static final int DEFAULT_RETENTION_DAYS = 7;

    @NonNull
    private Features features = new Features();

    @NonNull
    private Polling polling = new Polling();

    @NonNull
    private Sla sla = new Sla();

    private int retentionDays = DEFAULT_RETENTION_DAYS;

    public InteractiveInputGlobalConfig() {
        load();
    }

    /**
     * @return the singleton instance, or {@code null} only in the unusual case that the extension is
     *     not registered (e.g. some minimal test harnesses). Callers should treat {@code null} as
     *     "all defaults" via the {@code *OrDefault} helpers below.
     */
    public static InteractiveInputGlobalConfig get() {
        return GlobalConfiguration.all().get(InteractiveInputGlobalConfig.class);
    }

    @NonNull
    public Features getFeatures() {
        return features;
    }

    @DataBoundSetter
    public void setFeatures(@NonNull Features features) {
        this.features = features;
        save();
    }

    @NonNull
    public Polling getPolling() {
        return polling;
    }

    @DataBoundSetter
    public void setPolling(@NonNull Polling polling) {
        this.polling = polling;
        save();
    }

    @NonNull
    public Sla getSla() {
        return sla;
    }

    @DataBoundSetter
    public void setSla(@NonNull Sla sla) {
        this.sla = sla;
        save();
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    @DataBoundSetter
    public void setRetentionDays(int retentionDays) {
        this.retentionDays = Math.max(0, retentionDays);
        save();
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        // Feature flags are booleans and Stapler omits unchecked checkboxes, so start them all-off and
        // let the submitted form re-enable the checked ones (config.jelly renders every flag). Polling,
        // SLA and retention are not on this form (JCasC / Script Console only), so leave their current
        // values untouched rather than resetting them to defaults on every System save.
        this.features = allFeaturesOff();
        req.bindJSON(this, json);
        save();
        return true;
    }

    @NonNull
    private static Features allFeaturesOff() {
        Features f = new Features();
        f.setAskInteractiveStep(false);
        f.setRichModal(false);
        f.setRestApi(false);
        f.setInputStepBridge(false);
        f.setDashboardTile(false);
        return f;
    }

    // ---- Null-safe convenience accessors used across the plugin ----

    @NonNull
    public static Features featuresOrDefault() {
        InteractiveInputGlobalConfig c = get();
        return c != null ? c.getFeatures() : new Features();
    }

    public static int pollingIntervalSecondsOrDefault() {
        InteractiveInputGlobalConfig c = get();
        return c != null ? c.getPolling().getIntervalSeconds() : Polling.DEFAULT_INTERVAL_SECONDS;
    }

    public static int defaultSlaMinutesOrDefault() {
        InteractiveInputGlobalConfig c = get();
        return c != null ? c.getSla().getDefaultMinutes() : Sla.DEFAULT_MINUTES;
    }

    public static int retentionDaysOrDefault() {
        InteractiveInputGlobalConfig c = get();
        return c != null ? c.getRetentionDays() : DEFAULT_RETENTION_DAYS;
    }
}

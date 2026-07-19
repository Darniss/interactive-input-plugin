package io.jenkins.plugins.interactiveinput.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.util.ListBoxModel;
import java.util.List;
import jenkins.appearance.AppearanceCategory;
import jenkins.model.GlobalConfiguration;
import jenkins.model.GlobalConfigurationCategory;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Appearance-facing configuration for the plugin's notification surfaces (§6.4).
 *
 * <p>Kept deliberately separate from {@link InteractiveInputGlobalConfig} (which holds functional
 * flags under <em>Manage Jenkins → System</em>): Jenkins core guidance is that look-and-feel settings
 * belong in their own {@link AppearanceCategory} class so they surface under <em>Manage Jenkins →
 * Appearance</em>. Because {@code AppearanceCategory} has no {@code @Symbol}, JCasC maps this block to
 * the {@code appearance.interactiveInputAppearance} path (verified against
 * {@code GlobalConfigurationCategoryConfigurator}).
 *
 * <p>Three independent on/off switches plus an icon chooser:
 * <ul>
 *   <li>{@link #isNotificationCentre() notificationCentre} — the global bell (off by default). When
 *       on, it lists <em>all</em> answerable questions on the dashboard but scopes to the current
 *       pipeline's questions when viewing a job/build.</li>
 *   <li>{@link #isPerProjectCentre() perProjectCentre} — per-pipeline/per-build surfaces (sidebar
 *       page, build-history "awaiting input" badge, per-build audit view). On by default.</li>
 *   <li>{@link #isJobPageBox() jobPageBox} — the large inline box on the job page. On by default;
 *       independently switchable so an operator can keep the badge/sidebar without the big box.</li>
 *   <li>{@link #getIcon() icon} — which Ionicon represents interactive input across the bell, badge
 *       and sidebar.</li>
 * </ul>
 *
 * <p>Two further switches govern <em>who</em> sees and answers a notification (§user-scoped surfaces):
 * <ul>
 *   <li>{@link #isUserScopedNotifications() userScopedNotifications} — off by default (everyone who
 *       may answer sees a question). When on, the notification surfaces show a question only to the
 *       user who started the owning build; builds with no human starter (SCM/timer/upstream/system)
 *       stay visible to everyone since there is no owner to scope to.</li>
 *   <li>{@link #isLockToBuildStarter() lockToBuildStarter} — off by default. When on, only the build
 *       starter (or a Jenkins administrator) may <em>answer</em>; everyone else who could see it can
 *       still read it but the modal controls are locked. Builds with no human starter are not locked
 *       (otherwise no one could answer them). This is an additional restriction layered on top of the
 *       existing Job/Build permission and {@code submitterFilter} checks, never a relaxation.</li>
 * </ul>
 */
@Extension
@Symbol("interactiveInputAppearance")
public class InteractiveInputAppearanceConfig extends GlobalConfiguration {

    /** Selectable icons (Ionicon stems); the rendered class uses the {@code -outline} variant. */
    public static final List<String> ICON_CHOICES = List.of(
            "chatbubble-ellipses",
            "hand-left",
            "person-circle",
            "git-pull-request",
            "megaphone",
            "hourglass",
            "alert-circle",
            "notifications");

    /** Default icon: a speech bubble conveying "awaiting your response". */
    public static final String DEFAULT_ICON = "chatbubble-ellipses";

    private boolean notificationCentre;
    private boolean perProjectCentre = true;
    private boolean jobPageBox = true;
    private boolean userScopedNotifications;
    private boolean lockToBuildStarter;

    @NonNull
    private String icon = DEFAULT_ICON;

    public InteractiveInputAppearanceConfig() {
        load();
    }

    /**
     * @return the singleton, or {@code null} only if the extension is not registered (some minimal
     *     test harnesses). Callers should prefer the {@code *Enabled}/{@code *OrDefault} helpers.
     */
    @CheckForNull
    public static InteractiveInputAppearanceConfig get() {
        return GlobalConfiguration.all().get(InteractiveInputAppearanceConfig.class);
    }

    @Override
    @NonNull
    public GlobalConfigurationCategory getCategory() {
        return GlobalConfigurationCategory.get(AppearanceCategory.class);
    }

    public boolean isNotificationCentre() {
        return notificationCentre;
    }

    @DataBoundSetter
    public void setNotificationCentre(boolean notificationCentre) {
        this.notificationCentre = notificationCentre;
        save();
    }

    public boolean isPerProjectCentre() {
        return perProjectCentre;
    }

    @DataBoundSetter
    public void setPerProjectCentre(boolean perProjectCentre) {
        this.perProjectCentre = perProjectCentre;
        save();
    }

    public boolean isJobPageBox() {
        return jobPageBox;
    }

    @DataBoundSetter
    public void setJobPageBox(boolean jobPageBox) {
        this.jobPageBox = jobPageBox;
        save();
    }

    public boolean isUserScopedNotifications() {
        return userScopedNotifications;
    }

    @DataBoundSetter
    public void setUserScopedNotifications(boolean userScopedNotifications) {
        this.userScopedNotifications = userScopedNotifications;
        save();
    }

    public boolean isLockToBuildStarter() {
        return lockToBuildStarter;
    }

    @DataBoundSetter
    public void setLockToBuildStarter(boolean lockToBuildStarter) {
        this.lockToBuildStarter = lockToBuildStarter;
        save();
    }

    @NonNull
    public String getIcon() {
        return ICON_CHOICES.contains(icon) ? icon : DEFAULT_ICON;
    }

    @DataBoundSetter
    public void setIcon(@CheckForNull String icon) {
        this.icon = icon != null && ICON_CHOICES.contains(icon) ? icon : DEFAULT_ICON;
        save();
    }

    /** @return the theme-aware Jenkins symbol class for the configured icon (via ionicons-api). */
    @NonNull
    public String getIconClassName() {
        return iconClassName(getIcon());
    }

    /**
     * @param iconStem an Ionicon stem (validated against {@link #ICON_CHOICES}; unknown values fall
     *     back to {@link #DEFAULT_ICON})
     * @return the {@code symbol-<name>-outline plugin-ionicons-api} class Jenkins renders as an SVG
     */
    @NonNull
    public static String iconClassName(@CheckForNull String iconStem) {
        String stem = iconStem != null && ICON_CHOICES.contains(iconStem) ? iconStem : DEFAULT_ICON;
        return "symbol-" + stem + "-outline plugin-ionicons-api";
    }

    // ---- Null-safe convenience accessors used across the plugin ----

    /** @return whether the global notification bell should render. Off by default. */
    public static boolean notificationCentreEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c != null && c.isNotificationCentre();
    }

    /** @return whether per-pipeline/per-build surfaces are enabled. On by default. */
    public static boolean perProjectCentreEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c == null || c.isPerProjectCentre();
    }

    /** @return whether the large inline job-page box is enabled. On by default. */
    public static boolean jobPageBoxEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c == null || c.isJobPageBox();
    }

    /** @return whether notification surfaces are scoped to the build starter. Off by default. */
    public static boolean userScopedNotificationsEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c != null && c.isUserScopedNotifications();
    }

    /** @return whether only the build starter (or an admin) may answer. Off by default. */
    public static boolean lockToBuildStarterEnabled() {
        InteractiveInputAppearanceConfig c = get();
        return c != null && c.isLockToBuildStarter();
    }

    /** @return the configured icon's symbol class, or the default's when unconfigured. */
    @NonNull
    public static String iconClassNameOrDefault() {
        InteractiveInputAppearanceConfig c = get();
        return c != null ? c.getIconClassName() : iconClassName(DEFAULT_ICON);
    }

    /** Populates the icon dropdown on the Appearance config page (label ⇒ stem). */
    @NonNull
    public ListBoxModel doFillIconItems() {
        ListBoxModel m = new ListBoxModel();
        m.add("Speech bubble — awaiting your response", "chatbubble-ellipses");
        m.add("Raised hand — human action needed", "hand-left");
        m.add("Person — human-in-the-loop", "person-circle");
        m.add("Pull request — approval / review gate", "git-pull-request");
        m.add("Megaphone — needs attention", "megaphone");
        m.add("Hourglass — waiting / pending decision", "hourglass");
        m.add("Alert — attention needed", "alert-circle");
        m.add("Bell — classic notification", "notifications");
        return m;
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        // Rebind from scratch so unchecked boxes reset to false (checkbox fields are absent when off).
        this.notificationCentre = false;
        this.perProjectCentre = false;
        this.jobPageBox = false;
        this.userScopedNotifications = false;
        this.lockToBuildStarter = false;
        this.icon = DEFAULT_ICON;
        req.bindJSON(this, json);
        save();
        return true;
    }
}

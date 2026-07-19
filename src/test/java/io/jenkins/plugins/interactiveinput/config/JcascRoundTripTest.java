package io.jenkins.plugins.interactiveinput.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * JCasC round-trip (§7.3, §8.8): loading the documented YAML must wire the extensions, and exporting
 * must reproduce the configured values under the {@code interactiveInput} symbol.
 */
@WithJenkins
class JcascRoundTripTest {

    @Test
    void loadsFeatureFlagsFromYaml(JenkinsRule j) throws Exception {
        ConfigurationAsCode.get().configure(resource("jcasc-interactive-input.yml"));

        InteractiveInputGlobalConfig cfg = InteractiveInputGlobalConfig.get();
        assertNotNull(cfg);
        Features f = cfg.getFeatures();
        assertTrue(f.isAskInteractiveStep());
        assertTrue(f.isRestApi());
        assertTrue(f.isInputStepBridge(), "inputStepBridge overridden to true in YAML");
        assertFalse(f.isDashboardTile());
        assertEquals(30, cfg.getPolling().getIntervalSeconds());
        assertEquals(5, cfg.getSla().getDefaultMinutes());
        assertEquals(14, cfg.getRetentionDays());
    }

    @Test
    void exportsConfiguredValues(JenkinsRule j) throws Exception {
        ConfigurationAsCode.get().configure(resource("jcasc-interactive-input.yml"));

        String exported = export();
        assertTrue(exported.contains("interactiveInput"), () -> "export missing symbol:\n" + exported);
        assertTrue(exported.contains("inputStepBridge: true"), () -> "export missing bridge flag:\n" + exported);
        assertTrue(exported.contains("intervalSeconds: 30"), () -> "export missing polling:\n" + exported);
    }

    private static String resource(String name) {
        return Objects.requireNonNull(JcascRoundTripTest.class.getResource(name), "missing test resource " + name)
                .toString();
    }

    private static String export() throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ConfigurationAsCode.get().export(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}

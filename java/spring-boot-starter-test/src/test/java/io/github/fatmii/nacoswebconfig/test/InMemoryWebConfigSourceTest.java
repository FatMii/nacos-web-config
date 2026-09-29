package io.github.fatmii.nacoswebconfig.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import io.github.fatmii.nacoswebconfig.core.ConfigStatus;
import io.github.fatmii.nacoswebconfig.core.ExposureDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryWebConfigSourceTest {
    private static final ConfigRef UI = new ConfigRef("DEFAULT_GROUP", "ui.json");

    @Test
    void simulatesInvalidFailureAndRecoveryWhilePreservingLastKnownGood() {
        var source = new InMemoryWebConfigSource(Map.of("ui", UI));
        try (var runtime = ConfigRuntime.start(
                List.of(new ExposureDefinition("ui", UI, 65_536)), source)) {
            assertEquals(ConfigStatus.UNAVAILABLE, entry(runtime).status());

            source.invalid("ui");
            assertEquals(ConfigStatus.INVALID, entry(runtime).status());

            source.value("ui", "{\"version\":1}");
            var ready = entry(runtime);
            assertEquals(ConfigStatus.READY, ready.status());

            source.fail("ui");
            var unavailable = entry(runtime);
            assertEquals(ConfigStatus.UNAVAILABLE, unavailable.status());
            assertEquals(ready.valueJson(), unavailable.valueJson());

            source.recover("ui", "{\"version\":2}");
            assertEquals("{\"version\":2}", entry(runtime).valueJson());
        }
    }

    @Test
    void rejectsUnknownPublicAliasWithoutRevealingInternalReferences() {
        var source = new InMemoryWebConfigSource(Map.of("ui", UI));
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> source.value("secret", "{}"));

        assertTrue(failure.getMessage().contains("secret"));
        assertTrue(!failure.getMessage().contains("ui.json"));
    }

    private io.github.fatmii.nacoswebconfig.core.ConfigEntry entry(ConfigRuntime runtime) {
        return runtime.snapshot(Set.of("ui")).entries().get("ui");
    }
}

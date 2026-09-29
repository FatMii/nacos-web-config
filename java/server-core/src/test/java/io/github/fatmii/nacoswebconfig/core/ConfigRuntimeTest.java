package io.github.fatmii.nacoswebconfig.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigRuntimeTest {
    private static final ConfigRef UI_REF = new ConfigRef("WEB_DEMO", "web-demo.public.json");
    private static final ExposureDefinition UI = new ExposureDefinition("ui", UI_REF, 65_536);

    @Test
    void validJsonObjectBecomesReadySnapshot() {
        var source = new InMemoryConfigSource();
        try (var runtime = ConfigRuntime.start(List.of(UI), source)) {
            source.value(UI.ref(), "{\"banner\":{\"enabled\":true,\"text\":\"hello\"},\"refreshIntervalMs\":30000}");

            var snapshot = runtime.snapshot(Set.of("ui"));
            assertEquals(ConfigStatus.READY, snapshot.entries().get("ui").status());
            assertEquals(
                    "{\"banner\":{\"enabled\":true,\"text\":\"hello\"},\"refreshIntervalMs\":30000}",
                    snapshot.entries().get("ui").valueJson());
        }
    }

    @Test
    void invalidJsonKeepsLastKnownGoodAndReportsStableError() {
        var source = new InMemoryConfigSource();
        try (var runtime = ConfigRuntime.start(List.of(UI), source)) {
            source.value(UI_REF, "{\"theme\":\"dark\"}");
            var ready = runtime.snapshot(Set.of("ui")).entries().get("ui");

            source.value(UI_REF, "{not-json");

            var invalid = runtime.snapshot(Set.of("ui")).entries().get("ui");
            assertEquals(ConfigStatus.INVALID, invalid.status());
            assertEquals(ConfigErrorCode.INVALID_JSON, invalid.errorCode());
            assertEquals(ready.valueJson(), invalid.valueJson());
            assertEquals(ready.contentHash(), invalid.contentHash());
        }
    }

    @Test
    void rejectionListenerReportsEveryRejectedPublishWithContentHashOnly() {
        var source = new InMemoryConfigSource();
        var rejections = new ArrayList<String>();
        try (var runtime = ConfigRuntime.start(List.of(UI), source,
                (alias, code, hash) -> rejections.add(alias + "|" + code + "|" + hash))) {
            source.value(UI_REF, "{\"theme\":\"dark\"}");
            assertTrue(rejections.isEmpty(), "valid content must not report a rejection");

            source.value(UI_REF, "{not-json");
            source.value(UI_REF, "[1,2,3]");
            assertEquals(2, rejections.size());
            assertEquals("ui|INVALID_JSON|" + sha256Hex("{not-json"), rejections.get(0));
            assertEquals("ui|INVALID_JSON|" + sha256Hex("[1,2,3]"), rejections.get(1));

            source.value(UI_REF, "x".repeat(70_000));
            assertEquals(3, rejections.size());
            assertTrue(rejections.get(2).contains("|TOO_LARGE|"));

            // A repeated identical rejection still logs (operators need each attempt counted)
            // while the browser-visible entry stays unchanged.
            source.value(UI_REF, "x".repeat(70_000));
            assertEquals(4, rejections.size());
            assertEquals(ConfigStatus.INVALID,
                    runtime.snapshot(Set.of("ui")).entries().get("ui").status());
        }
    }

    private static String sha256Hex(String content) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder("sha256:");
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void topLevelArrayIsInvalid() {
        var source = new InMemoryConfigSource();
        try (var runtime = ConfigRuntime.start(List.of(UI), source)) {
            source.value(UI_REF, "[1,2,3]");
            var entry = runtime.snapshot(Set.of("ui")).entries().get("ui");
            assertEquals(ConfigStatus.INVALID, entry.status());
            assertFalse(entry.hasValue());
        }
    }

    @Test
    void utf8ByteLimitKeepsLastKnownGood() {
        var source = new InMemoryConfigSource();
        var tiny = new ExposureDefinition("ui", UI_REF, 12);
        try (var runtime = ConfigRuntime.start(List.of(tiny), source)) {
            source.value(UI_REF, "{\"ok\":true}");
            source.value(UI_REF, "{\"值\":\"太长\"}");
            var entry = runtime.snapshot(Set.of("ui")).entries().get("ui");
            assertEquals(ConfigErrorCode.TOO_LARGE, entry.errorCode());
            assertEquals("{\"ok\":true}", entry.valueJson());
            assertTrue("{\"值\":\"太长\"}".getBytes(StandardCharsets.UTF_8).length > 12);
        }
    }

    @Test
    void deleteClearsLastKnownGoodAndLaterInvalidDoesNotResurrectIt() {
        var source = new InMemoryConfigSource();
        try (var runtime = ConfigRuntime.start(List.of(UI), source)) {
            source.value(UI_REF, "{\"theme\":\"dark\"}");
            source.deleted(UI_REF);
            var deleted = runtime.snapshot(Set.of("ui")).entries().get("ui");
            assertEquals(ConfigStatus.DELETED, deleted.status());
            assertFalse(deleted.hasValue());

            source.value(UI_REF, "broken");
            var invalid = runtime.snapshot(Set.of("ui")).entries().get("ui");
            assertEquals(ConfigStatus.INVALID, invalid.status());
            assertFalse(invalid.hasValue());
        }
    }

    @Test
    void unavailableKeepsLastKnownGood() {
        var source = new InMemoryConfigSource();
        try (var runtime = ConfigRuntime.start(List.of(UI), source)) {
            source.value(UI_REF, "{\"theme\":\"dark\"}");
            source.unavailable(UI_REF);
            var entry = runtime.snapshot(Set.of("ui")).entries().get("ui");
            assertEquals(ConfigStatus.UNAVAILABLE, entry.status());
            assertEquals(ConfigErrorCode.SOURCE_UNAVAILABLE, entry.errorCode());
            assertEquals("{\"theme\":\"dark\"}", entry.valueJson());
        }
    }

    @Test
    void subscriberReceivesSnapshotBeforeChangesAndDuplicateContentIsSuppressed() {
        var source = new InMemoryConfigSource();
        try (var runtime = ConfigRuntime.start(List.of(UI), source)) {
            var events = new ArrayList<String>();
            runtime.subscribe(Set.of("ui"), new ConfigSubscriber() {
                @Override
                public void onSnapshot(ConfigSnapshot snapshot) {
                    events.add("snapshot:" + snapshot.entries().get("ui").status());
                }

                @Override
                public void onChange(ConfigChange change) {
                    events.add("change:" + change.entry().valueJson());
                }
            });

            source.value(UI_REF, "{\"version\":1}");
            source.value(UI_REF, "{\"version\":1}");

            assertEquals(List.of("snapshot:UNAVAILABLE", "change:{\"version\":1}"), events);
        }
    }

    @Test
    void failingSubscriberDoesNotBlockAnotherAndCancellationIsIdempotent() {
        var source = new InMemoryConfigSource();
        try (var runtime = ConfigRuntime.start(List.of(UI), source)) {
            runtime.subscribe(Set.of("ui"), new ConfigSubscriber() {
                @Override public void onSnapshot(ConfigSnapshot snapshot) {}
                @Override public void onChange(ConfigChange change) { throw new IllegalStateException("boom"); }
            });
            var changes = new ArrayList<ConfigChange>();
            var subscription = runtime.subscribe(Set.of("ui"), subscriber(changes));

            source.value(UI_REF, "{\"version\":1}");
            subscription.close();
            subscription.close();
            source.value(UI_REF, "{\"version\":2}");

            assertEquals(1, changes.size());
        }
    }

    @Test
    void closeReleasesSourceAndDropsLateCallbacks() {
        var source = new InMemoryConfigSource();
        var runtime = ConfigRuntime.start(List.of(UI), source);
        runtime.close();
        runtime.close();
        source.lateValue(UI_REF, "{\"late\":true}");

        assertTrue(source.watchClosed());
        assertTrue(source.sourceClosed());
        assertThrows(IllegalStateException.class, () -> runtime.snapshot(Set.of("ui")));
    }

    @Test
    void registryRejectsUnknownAliasesAndAmbiguousDefinitions() {
        var source = new InMemoryConfigSource();
        assertThrows(IllegalArgumentException.class, () -> ConfigRuntime.start(List.of(), source));
        assertThrows(IllegalArgumentException.class, () -> ConfigRuntime.start(List.of(UI, UI), source));
        assertThrows(IllegalArgumentException.class, () -> ConfigRuntime.start(
                List.of(UI, new ExposureDefinition("other", UI_REF, 100)), source));
        try (var runtime = ConfigRuntime.start(List.of(UI), source)) {
            assertThrows(IllegalArgumentException.class, () -> runtime.snapshot(Set.of("missing")));
        }
    }

    private static ConfigSubscriber subscriber(List<ConfigChange> changes) {
        return new ConfigSubscriber() {
            @Override public void onSnapshot(ConfigSnapshot snapshot) {}
            @Override public void onChange(ConfigChange change) { changes.add(change); }
        };
    }
}

package io.github.fatmii.nacoswebconfig.autoconfigure;

import io.github.fatmii.nacoswebconfig.core.ConfigEntry;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Reports whether Nacos is currently reachable and what each exposure is serving.
 *
 * <p>Only the source reachability changes the status. An exposure that is invalid or temporarily
 * unavailable still serves its last-known-good value to browsers, so it is reported as detail for
 * operators to correlate instead of failing the host application's readiness probe. Raw
 * configuration content and content hashes are never exposed.
 */
final class NacosWebConfigHealthIndicator extends AbstractHealthIndicator {
    private static final String SOURCE_KEY = "source";
    private static final String EXPOSURES_KEY = "exposures";

    private final ConfigRuntime runtime;

    NacosWebConfigHealthIndicator(ConfigRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean sourceAvailable = runtime.sourceAvailable();
        var exposures = new TreeMap<String, Map<String, Object>>();
        runtime.snapshot(runtime.aliases()).entries().forEach((alias, entry) -> {
            exposures.put(alias, describe(entry));
        });
        builder.status(sourceAvailable ? Status.UP : Status.DOWN)
                .withDetail(SOURCE_KEY, sourceAvailable ? "available" : "unavailable")
                .withDetail(EXPOSURES_KEY, exposures);
    }

    private static Map<String, Object> describe(ConfigEntry entry) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("status", entry.status().name().toLowerCase());
        detail.put("hasValue", entry.hasValue());
        if (entry.errorCode() != null) {
            detail.put("errorCode", entry.errorCode().name());
        }
        return Map.copyOf(detail);
    }
}

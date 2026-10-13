package io.github.fatmii.nacoswebconfig.autoconfigure;

import io.github.fatmii.nacoswebconfig.core.ConfigErrorCode;
import io.github.fatmii.nacoswebconfig.core.ConfigRejectionListener;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts content the runtime refused and publishes it as {@code nacos.webconfig.rejections}.
 *
 * <p>Tags are the fixed reason codes only: the exposure alias and the rejected content's SHA-256
 * stay in the operator log, and the rejected body is never retained anywhere. Counting happens in
 * memory and is published through {@link MeterBinder}, so this works whether Actuator created one
 * registry or several, and stays inert when the host has no registry at all.
 */
final class NacosWebConfigRejectionMetrics implements ConfigRejectionListener, MeterBinder {
    private static final String METRIC_NAME = "nacos.webconfig.rejections";

    private final ConcurrentMap<ConfigErrorCode, LongAdder> counts = new ConcurrentHashMap<>();

    @Override
    public void onRejected(String alias, ConfigErrorCode code, String rejectedContentHash) {
        counts.computeIfAbsent(code, ignored -> new LongAdder()).increment();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (var code : ConfigErrorCode.values()) {
            FunctionCounter.builder(METRIC_NAME, this, metrics -> metrics.count(code))
                    .tag("code", code.name())
                    .description("Publishes this runtime refused and kept serving the previous value")
                    .register(registry);
        }
    }

    private double count(ConfigErrorCode code) {
        var adder = counts.get(code);
        return adder == null ? 0D : adder.sum();
    }
}

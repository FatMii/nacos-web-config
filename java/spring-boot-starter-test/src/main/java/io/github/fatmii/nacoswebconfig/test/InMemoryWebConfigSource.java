package io.github.fatmii.nacoswebconfig.test;

import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.ConfigSink;
import io.github.fatmii.nacoswebconfig.core.ConfigSource;
import io.github.fatmii.nacoswebconfig.core.SourceError;
import io.github.fatmii.nacoswebconfig.core.SourceEvent;
import io.github.fatmii.nacoswebconfig.core.Watch;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Test-controlled configuration source addressed by the same public aliases used by browsers.
 *
 * <p>Tests can drive the real core, MVC endpoint, and SSE protocol without creating or mocking a
 * Nacos client. The configured group and dataId remain hidden behind the alias map.
 */
public final class InMemoryWebConfigSource implements ConfigSource {
    private final Object lock = new Object();
    private final Map<String, ConfigRef> refsByAlias;
    private final Map<String, SourceEvent> current = new LinkedHashMap<>();
    private ConfigSink sink;
    private Set<ConfigRef> watchedRefs = Set.of();
    private boolean watchActive;
    private boolean closed;

    InMemoryWebConfigSource(Map<String, ConfigRef> refsByAlias) {
        this.refsByAlias = Map.copyOf(refsByAlias);
    }

    /** Publishes valid or intentionally invalid raw JSON for one exposure. */
    public void value(String alias, String json) {
        emit(alias, ref -> new SourceEvent.Value(ref, Objects.requireNonNull(json, "json")));
    }

    /** Publishes a convenient malformed JSON value for validation tests. */
    public void invalid(String alias) {
        value(alias, "{invalid-json");
    }

    /** Publishes an authoritative deletion for one exposure. */
    public void delete(String alias) {
        emit(alias, SourceEvent.Deleted::new);
    }

    /** Marks one exposure temporarily unavailable while preserving its last-known-good value. */
    public void fail(String alias) {
        emit(alias, ref -> new SourceEvent.Unavailable(ref, SourceError.UNAVAILABLE));
    }

    /** Recovers an unavailable exposure with a new authoritative value. */
    public void recover(String alias, String json) {
        value(alias, json);
    }

    @Override
    public Watch watch(Set<ConfigRef> refs, ConfigSink sink) {
        Objects.requireNonNull(refs, "refs");
        Objects.requireNonNull(sink, "sink");
        synchronized (lock) {
            ensureOpen();
            if (watchActive) throw new IllegalStateException("only one runtime watch is supported");
            if (!refsByAlias.values().containsAll(refs)) {
                throw new IllegalArgumentException("unknown configuration reference");
            }
            this.sink = sink;
            watchedRefs = Set.copyOf(refs);
            watchActive = true;
            refsByAlias.forEach((alias, ref) -> {
                if (refs.contains(ref)) {
                    sink.accept(current.getOrDefault(
                            alias, new SourceEvent.Unavailable(ref, SourceError.UNAVAILABLE)));
                }
            });
        }
        return this::closeWatch;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            watchActive = false;
            sink = null;
            watchedRefs = Set.of();
        }
    }

    private void emit(String alias, java.util.function.Function<ConfigRef, SourceEvent> eventFactory) {
        ConfigSink target;
        SourceEvent event;
        synchronized (lock) {
            ensureOpen();
            var ref = refsByAlias.get(alias);
            if (ref == null) throw new IllegalArgumentException("unknown exposure alias: " + alias);
            event = eventFactory.apply(ref);
            current.put(alias, event);
            target = watchActive && watchedRefs.contains(ref) ? sink : null;
        }
        if (target != null) target.accept(event);
    }

    private void closeWatch() {
        synchronized (lock) {
            watchActive = false;
            sink = null;
            watchedRefs = Set.of();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("test source is closed");
    }
}

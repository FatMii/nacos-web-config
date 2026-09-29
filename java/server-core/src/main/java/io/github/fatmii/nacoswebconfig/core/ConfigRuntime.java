package io.github.fatmii.nacoswebconfig.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Framework-independent configuration runtime.
 *
 * <p>The runtime owns a fixed allowlist of {@link ExposureDefinition exposures}. It converts raw
 * {@link SourceEvent source events} into immutable {@link ConfigEntry entries}, validates that
 * values are size-bounded JSON objects, retains a last-known-good value across invalid content and
 * temporary source failures, and distributes observable state changes to subscribers.
 *
 * <p>Snapshot creation, subscriber registration, and state replacement share one synchronization
 * boundary. This guarantees that a subscriber observes its complete initial snapshot before any
 * later change. Subscriber callbacks are serialized per subscription and exceptions are isolated.
 *
 * <p>This type is thread-safe. Closing it is idempotent, cancels downstream subscriptions, closes
 * the upstream watch and source, and rejects late source callbacks.
 */
public final class ConfigRuntime implements AutoCloseable {
    private final Object lock = new Object();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ExposureDefinition> definitions;
    private final Map<ConfigRef, String> aliasesByRef;
    private final Map<String, ConfigEntry> entries = new HashMap<>();
    private final ArrayList<SubscriberState> subscribers = new ArrayList<>();
    private final ConfigSource source;
    private final ConfigRejectionListener rejectionListener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Watch watch;

    private ConfigRuntime(
            Collection<ExposureDefinition> definitions,
            ConfigSource source,
            ConfigRejectionListener rejectionListener) {
        this.source = Objects.requireNonNull(source, "source");
        this.rejectionListener = rejectionListener;
        var byAlias = new LinkedHashMap<String, ExposureDefinition>();
        var byRef = new HashMap<ConfigRef, String>();
        for (var definition : definitions) {
            if (byAlias.putIfAbsent(definition.alias(), definition) != null) {
                throw new IllegalArgumentException("duplicate exposure alias: " + definition.alias());
            }
            if (byRef.putIfAbsent(definition.ref(), definition.alias()) != null) {
                throw new IllegalArgumentException("a config ref cannot have multiple aliases: " + definition.ref());
            }
            entries.put(definition.alias(), ConfigEntry.unavailable());
        }
        if (byAlias.isEmpty()) {
            throw new IllegalArgumentException("at least one exposure is required");
        }
        this.definitions = Map.copyOf(byAlias);
        this.aliasesByRef = Map.copyOf(byRef);
    }

    /**
     * Creates the fixed exposure registry and begins watching all referenced configurations.
     *
     * @param definitions non-empty exposure allowlist
     * @param source source owned by the returned runtime
     * @return started runtime
     * @throws IllegalArgumentException for duplicate aliases, duplicate references, or no exposures
     * @throws ConfigSourceException if the source cannot establish its watch
     */
    public static ConfigRuntime start(Collection<ExposureDefinition> definitions, ConfigSource source) {
        return start(definitions, source, null);
    }

    /**
     * Same as {@link #start(Collection, ConfigSource)}, additionally reporting every content
     * rejection to an operator hook. Listener failures never affect state or delivery.
     */
    public static ConfigRuntime start(
            Collection<ExposureDefinition> definitions,
            ConfigSource source,
            ConfigRejectionListener rejectionListener) {
        Objects.requireNonNull(definitions, "definitions");
        var runtime = new ConfigRuntime(definitions, source, rejectionListener);
        runtime.watch = source.watch(runtime.aliasesByRef.keySet(), runtime::accept);
        return runtime;
    }

    /**
     * Returns an immutable point-in-time view of the requested logical aliases.
     *
     * @param aliases allowlisted aliases to include
     * @return current snapshot
     * @throws IllegalArgumentException if an alias is unknown
     * @throws IllegalStateException if this runtime is closed
     */
    public ConfigSnapshot snapshot(Set<String> aliases) {
        Objects.requireNonNull(aliases, "aliases");
        synchronized (lock) {
            ensureOpen();
            var selected = new LinkedHashMap<String, ConfigEntry>();
            for (var alias : aliases) {
                var entry = entries.get(alias);
                if (entry == null) {
                    throw new IllegalArgumentException("unknown exposure alias");
                }
                selected.put(alias, entry);
            }
            return new ConfigSnapshot(selected);
        }
    }

    /**
     * Registers a subscriber and delivers one complete snapshot before later changes.
     *
     * @param aliases allowlisted aliases to observe
     * @param subscriber consumer of the snapshot and changes
     * @return idempotent cancellation handle
     * @throws IllegalArgumentException if an alias is unknown
     * @throws IllegalStateException if this runtime is closed
     */
    public Subscription subscribe(Set<String> aliases, ConfigSubscriber subscriber) {
        Objects.requireNonNull(aliases, "aliases");
        Objects.requireNonNull(subscriber, "subscriber");
        SubscriberState state;
        synchronized (lock) {
            ensureOpen();
            // Registering and copying the snapshot are atomic with state replacement. Otherwise a
            // concurrent change could be delivered before, or be absent from, the initial snapshot.
            var snapshot = snapshotLocked(aliases);
            state = new SubscriberState(Set.copyOf(aliases), subscriber);
            state.enqueue(new SnapshotDelivery(snapshot));
            subscribers.add(state);
        }
        state.drain();
        var subscribed = state;
        return () -> cancel(subscribed);
    }

    private void accept(SourceEvent event) {
        ArrayList<SubscriberState> pending = new ArrayList<>();
        Rejection rejection = null;
        synchronized (lock) {
            if (closed.get()) {
                return;
            }
            var alias = aliasesByRef.get(event.ref());
            if (alias == null) {
                return;
            }
            var current = entries.get(alias);
            var next = transition(definitions.get(alias), current, event);
            if (next.status() == ConfigStatus.INVALID && event instanceof SourceEvent.Value value) {
                // Every rejected publish is reported even when the retained entry is unchanged;
                // the browser deduplicates by entry equality, operators need each attempt logged.
                rejection = new Rejection(alias, next.errorCode(), sha256(value.content()));
            }
            // The content hash is part of ConfigEntry, so equal entries represent no externally
            // observable change and must not trigger redundant browser work.
            if (next.equals(current)) {
                if (rejection != null) {
                    fireRejection(rejection);
                }
                return;
            }
            entries.put(alias, next);
            var change = new ConfigChange(alias, next);
            for (var subscriber : subscribers) {
                if (subscriber.aliases.contains(alias)) {
                    subscriber.enqueue(new ChangeDelivery(change));
                    pending.add(subscriber);
                }
            }
        }
        if (rejection != null) {
            fireRejection(rejection);
        }
        for (var subscriber : pending) {
            subscriber.drain();
        }
    }

    private record Rejection(String alias, ConfigErrorCode code, String rejectedContentHash) {}

    private void fireRejection(Rejection rejection) {
        if (rejectionListener == null) {
            return;
        }
        try {
            rejectionListener.onRejected(rejection.alias(), rejection.code(), rejection.rejectedContentHash());
        } catch (RuntimeException ignored) {
            // Diagnostics must never alter state or delivery.
        }
    }

    private ConfigSnapshot snapshotLocked(Set<String> aliases) {
        var selected = new LinkedHashMap<String, ConfigEntry>();
        for (var alias : aliases) {
            var entry = entries.get(alias);
            if (entry == null) {
                throw new IllegalArgumentException("unknown exposure alias");
            }
            selected.put(alias, entry);
        }
        return new ConfigSnapshot(selected);
    }

    private void cancel(SubscriberState subscriber) {
        synchronized (lock) {
            if (subscriber.cancel()) {
                subscribers.remove(subscriber);
            }
        }
    }

    private ConfigEntry transition(ExposureDefinition definition, ConfigEntry current, SourceEvent event) {
        if (event instanceof SourceEvent.Deleted) {
            // Deletion is authoritative. Keeping the old value here could resurrect configuration
            // that its owner explicitly removed.
            return new ConfigEntry(ConfigStatus.DELETED, null, null, null);
        }
        if (event instanceof SourceEvent.Unavailable) {
            // Unavailability says nothing about whether the configuration still exists, so preserve
            // the last valid value while clearly marking it stale for downstream adapters.
            return new ConfigEntry(
                    ConfigStatus.UNAVAILABLE,
                    current.valueJson(),
                    current.contentHash(),
                    ConfigErrorCode.SOURCE_UNAVAILABLE);
        }
        var content = ((SourceEvent.Value) event).content();
        if (content.getBytes(StandardCharsets.UTF_8).length > definition.maxBytes()) {
            return invalid(current, ConfigErrorCode.TOO_LARGE);
        }
        try {
            var tree = objectMapper.readTree(content);
            if (tree == null || !tree.isObject()) {
                return invalid(current, ConfigErrorCode.INVALID_JSON);
            }
            return new ConfigEntry(ConfigStatus.READY, content, sha256(content), null);
        } catch (Exception ignored) {
            return invalid(current, ConfigErrorCode.INVALID_JSON);
        }
    }

    private static ConfigEntry invalid(ConfigEntry current, ConfigErrorCode code) {
        return new ConfigEntry(ConfigStatus.INVALID, current.valueJson(), current.contentHash(), code);
    }

    private static String sha256(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder("sha256:");
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("runtime is closed");
        }
    }

    /**
     * Stops all delivery and releases the upstream watch and source. Repeated calls are safe.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            for (var subscriber : subscribers) {
                subscriber.cancel();
            }
            subscribers.clear();
        }
        try {
            if (watch != null) {
                watch.close();
            }
        } finally {
            source.close();
        }
    }

    private sealed interface Delivery {
        void send(ConfigSubscriber subscriber);
    }

    private record SnapshotDelivery(ConfigSnapshot snapshot) implements Delivery {
        @Override
        public void send(ConfigSubscriber subscriber) {
            subscriber.onSnapshot(snapshot);
        }
    }

    private record ChangeDelivery(ConfigChange change) implements Delivery {
        @Override
        public void send(ConfigSubscriber subscriber) {
            subscriber.onChange(change);
        }
    }

    private static final class SubscriberState {
        private final Set<String> aliases;
        private final ConfigSubscriber subscriber;
        private final ArrayDeque<Delivery> deliveries = new ArrayDeque<>();
        private boolean active = true;
        private boolean draining;

        private SubscriberState(Set<String> aliases, ConfigSubscriber subscriber) {
            this.aliases = aliases;
            this.subscriber = subscriber;
        }

        synchronized void enqueue(Delivery delivery) {
            if (active) {
                deliveries.add(delivery);
            }
        }

        void drain() {
            synchronized (this) {
                if (!active || draining) {
                    return;
                }
                draining = true;
            }
            while (true) {
                Delivery delivery;
                synchronized (this) {
                    delivery = deliveries.poll();
                    if (delivery == null || !active) {
                        draining = false;
                        return;
                    }
                }
                try {
                    delivery.send(subscriber);
                } catch (RuntimeException ignored) {
                    // User code is outside the core's trust boundary. One faulty consumer must not
                    // corrupt the hub or prevent healthy consumers from receiving the same change.
                }
            }
        }

        synchronized boolean cancel() {
            if (!active) {
                return false;
            }
            active = false;
            deliveries.clear();
            return true;
        }
    }
}

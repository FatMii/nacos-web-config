package io.github.fatmii.nacoswebconfig.mvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fatmii.nacoswebconfig.core.ConfigChange;
import io.github.fatmii.nacoswebconfig.core.ConfigEntry;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import io.github.fatmii.nacoswebconfig.core.ConfigSnapshot;
import io.github.fatmii.nacoswebconfig.core.ConfigSubscriber;
import io.github.fatmii.nacoswebconfig.core.Subscription;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

final class WebConfigStreamService implements SmartLifecycle, AutoCloseable {
    private final ConfigRuntime runtime;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<SseEmitter, StreamSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final ScheduledExecutorService heartbeats;
    private final ExecutorService sends;
    private final long connectionTimeoutMillis;
    private final int maxConnections;
    private final long maxPendingBytes;

    WebConfigStreamService(
            ConfigRuntime runtime,
            ObjectMapper mapper,
            Duration heartbeat,
            Duration connectionTimeout,
            int maxConnections,
            long maxPendingBytes) {
        this.runtime = runtime;
        this.mapper = mapper;
        if (heartbeat.toMillis() <= 0) {
            throw new IllegalArgumentException("heartbeat must be positive");
        }
        if (connectionTimeout.toMillis() <= 0) {
            throw new IllegalArgumentException("connectionTimeout must be positive");
        }
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxConnections must be positive");
        }
        if (maxPendingBytes <= 0) {
            throw new IllegalArgumentException("maxPendingBytes must be positive");
        }
        this.connectionTimeoutMillis = connectionTimeout.toMillis();
        this.maxConnections = maxConnections;
        this.maxPendingBytes = maxPendingBytes;
        this.sends = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "nacos-web-config-send");
            thread.setDaemon(true);
            return thread;
        });
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "nacos-web-config-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeats.scheduleAtFixedRate(
                this::heartbeatAll,
                heartbeat.toMillis(),
                heartbeat.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    SseEmitter open(List<String> keys) {
        if (!running.get()) throw WebConfigRequestException.moduleStopped();
        acquireConnection();
        var emitter = new SseEmitter(connectionTimeoutMillis);
        var session = new StreamSession(emitter);
        emitter.onCompletion(() -> closeSession(session, false));
        emitter.onTimeout(() -> closeSession(session, false));
        emitter.onError(error -> closeSession(session, false));
        var streamId = UUID.randomUUID().toString();
        var seq = new AtomicLong();
        try {
            var subscription = runtime.subscribe(Set.copyOf(keys), new ConfigSubscriber() {
                @Override public void onSnapshot(ConfigSnapshot snapshot) {
                    enqueue(session, "snapshot", Map.of(
                            "protocol", 1, "streamId", streamId, "seq", 0,
                            "entries", wireEntries(snapshot.entries())));
                }
                @Override public void onChange(ConfigChange change) {
                    enqueue(session, "change", Map.of(
                            "protocol", 1, "streamId", streamId, "seq", seq.incrementAndGet(),
                            "key", change.alias(), "entry", wire(change.entry())));
                }
            });
            session.attach(subscription);
            register(session);
        } catch (RuntimeException exception) {
            closeSession(session, true);
            throw exception;
        }
        return emitter;
    }

    void validate(List<String> keys) {
        try {
            runtime.snapshot(Set.copyOf(keys));
        } catch (IllegalArgumentException exception) {
            throw WebConfigRequestException.unknownKey();
        }
    }

    private void acquireConnection() {
        int current;
        do {
            current = activeConnections.get();
            if (current >= maxConnections) throw WebConfigRequestException.connectionLimit();
        } while (!activeConnections.compareAndSet(current, current + 1));
    }

    private void heartbeatAll() {
        sessions.values().forEach(session -> enqueue(
                session, SseEmitter.event().comment("ping"), ":ping\n\n".getBytes(StandardCharsets.UTF_8).length));
    }

    @Override
    public void close() {
        stop();
    }

    @Override public void start() {
        running.set(true);
    }

    @Override public boolean isRunning() {
        return running.get();
    }

    @Override public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        sessions.values().forEach(session -> closeSession(session, true));
        heartbeats.shutdownNow();
        sends.shutdownNow();
    }

    private Map<String, Object> wireEntries(Map<String, ConfigEntry> entries) {
        var result = new LinkedHashMap<String, Object>();
        entries.forEach((key, value) -> result.put(key, wire(value)));
        return result;
    }

    private Map<String, Object> wire(ConfigEntry entry) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", entry.status().name().toLowerCase());
        result.put("hasValue", entry.hasValue());
        if (entry.hasValue()) {
            try {
                result.put("value", mapper.readTree(entry.valueJson()));
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            result.put("contentHash", entry.contentHash());
        }
        if (entry.errorCode() != null) result.put("errorCode", entry.errorCode().name());
        return result;
    }

    private void enqueue(StreamSession session, String name, Object payload) {
        try {
            var json = mapper.writeValueAsString(payload);
            var bytes = ("event:" + name + "\ndata:" + json + "\n\n")
                    .getBytes(StandardCharsets.UTF_8).length;
            enqueue(session, SseEmitter.event().name(name).data(json), bytes);
        } catch (IOException exception) {
            closeSession(session, true);
        }
    }

    private void enqueue(
            StreamSession session, SseEmitter.SseEventBuilder event, long bytes) {
        boolean schedule;
        boolean overflow;
        synchronized (session) {
            if (session.closed.get()) return;
            if (session.pendingBytes + bytes > maxPendingBytes) {
                schedule = false;
                overflow = true;
            } else {
                session.queue.addLast(new PendingEvent(event, bytes));
                session.pendingBytes += bytes;
                schedule = !session.draining;
                session.draining = true;
                overflow = false;
            }
        }
        if (overflow) {
            closeSession(session, true);
        } else if (schedule) {
            try {
                sends.execute(() -> drain(session));
            } catch (RuntimeException exception) {
                closeSession(session, true);
            }
        }
    }

    private void drain(StreamSession session) {
        while (true) {
            PendingEvent pending;
            synchronized (session) {
                if (session.closed.get()) return;
                pending = session.queue.peekFirst();
                if (pending == null) {
                    session.draining = false;
                    return;
                }
            }
            try {
                session.emitter.send(pending.event());
            } catch (IOException | RuntimeException exception) {
                // A failed socket write is already handled by the Servlet container. Re-dispatching
                // it through completeWithError would turn an ordinary disconnect into an ERROR log.
                closeSession(session, false);
                return;
            }
            synchronized (session) {
                if (session.queue.pollFirst() == pending) {
                    session.pendingBytes -= pending.bytes();
                }
            }
        }
    }

    private void register(StreamSession session) {
        // Snapshot delivery happens synchronously during subscribe. Publish the session to the
        // heartbeat task only afterwards so snapshot remains the first event under every race.
        synchronized (session) {
            if (!session.closed.get()) sessions.put(session.emitter, session);
        }
    }

    private void closeSession(StreamSession session, boolean completeEmitter) {
        if (!session.closed.compareAndSet(false, true)) return;
        sessions.remove(session.emitter, session);
        synchronized (session) {
            session.queue.clear();
            session.pendingBytes = 0;
        }
        var subscription = session.subscription;
        if (subscription != null) subscription.close();
        activeConnections.decrementAndGet();
        if (!completeEmitter) return;
        try {
            // Response completion can itself block behind a full socket buffer. It must never run on
            // the Nacos callback thread after the logical connection has already been discarded.
            sends.execute(session.emitter::complete);
        } catch (RuntimeException ignored) {
            // The service is already stopping; logical cleanup above is complete.
        }
    }

    private record PendingEvent(SseEmitter.SseEventBuilder event, long bytes) {}

    private static final class StreamSession {
        private final SseEmitter emitter;
        private final ArrayDeque<PendingEvent> queue = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Subscription subscription;
        private long pendingBytes;
        private boolean draining;

        private StreamSession(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private void attach(Subscription subscription) {
            this.subscription = subscription;
            if (closed.get()) subscription.close();
        }
    }
}

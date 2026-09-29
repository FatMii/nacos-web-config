package io.github.fatmii.nacoswebconfig.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.ConfigSink;
import io.github.fatmii.nacoswebconfig.core.ConfigSource;
import io.github.fatmii.nacoswebconfig.core.ConfigSourceException;
import io.github.fatmii.nacoswebconfig.core.SourceEvent;
import io.github.fatmii.nacoswebconfig.core.SourceError;
import io.github.fatmii.nacoswebconfig.core.Watch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapts the stable Nacos {@link ConfigService} API to the core {@link ConfigSource} contract.
 *
 * <p>The adapter treats a {@code null} callback as an authoritative deletion, matching the behavior
 * verified against Nacos Server 2.5.3 and 3.2.3. Nacos callbacks only copy the value into a bounded,
 * adapter-owned executor; JSON parsing and state management remain in server-core.
 */
public final class NacosConfigSource implements ConfigSource {
    private static final int CALLBACK_CAPACITY = 1_024;
    private static final Duration DEFAULT_RECHECK_INTERVAL = Duration.ofSeconds(5);

    private final ConfigService service;
    private final long timeoutMillis;
    private final boolean ownsService;
    private final ThreadPoolExecutor callbacks;
    private final ScheduledThreadPoolExecutor healthChecks;
    private final ArrayList<NacosWatch> watches = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private NacosConfigSource(
            ConfigService service, Duration timeout, Duration recheckInterval, boolean ownsService) {
        this.service = Objects.requireNonNull(service, "service");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Objects.requireNonNull(recheckInterval, "recheckInterval");
        if (recheckInterval.isZero() || recheckInterval.isNegative()) {
            throw new IllegalArgumentException("recheckInterval must be positive");
        }
        this.timeoutMillis = timeout.toMillis();
        this.ownsService = ownsService;
        ThreadFactory threads = task -> {
            var thread = new Thread(task, "nacos-web-config-callback");
            thread.setDaemon(true);
            return thread;
        };
        this.callbacks = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CALLBACK_CAPACITY),
                threads,
                new ThreadPoolExecutor.DiscardOldestPolicy());
        ThreadFactory healthThreads = task -> {
            var thread = new Thread(task, "nacos-web-config-health");
            thread.setDaemon(true);
            return thread;
        };
        this.healthChecks = new ScheduledThreadPoolExecutor(1, healthThreads);
        this.healthChecks.setRemoveOnCancelPolicy(true);
        this.healthChecks.scheduleWithFixedDelay(
                this::recheck,
                recheckInterval.toMillis(),
                recheckInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Creates an adapter that never shuts down the host-owned ConfigService.
     *
     * @param service the ConfigService owned by the host application
     * @param timeout maximum duration of a Nacos read
     * @return a source that removes its listeners but leaves {@code service} running
     */
    public static NacosConfigSource borrowed(ConfigService service, Duration timeout) {
        return new NacosConfigSource(service, timeout, DEFAULT_RECHECK_INTERVAL, false);
    }

    static NacosConfigSource borrowed(
            ConfigService service, Duration timeout, Duration recheckInterval) {
        return new NacosConfigSource(service, timeout, recheckInterval, false);
    }

    /**
     * Creates an adapter that shuts down the transferred ConfigService when closed.
     *
     * @param service the ConfigService whose ownership is transferred to this source
     * @param timeout maximum duration of a Nacos read
     * @return a source that owns {@code service}
     */
    public static NacosConfigSource owned(ConfigService service, Duration timeout) {
        return new NacosConfigSource(service, timeout, DEFAULT_RECHECK_INTERVAL, true);
    }

    /**
     * Creates and owns a Nacos client configured by the supplied standard Nacos properties.
     *
     * <p>Properties such as {@code serverAddr}, {@code namespace}, username, and password are
     * interpreted by the official Nacos client. Closing this source also closes that client.
     *
     * @param properties standard Nacos client properties, including namespace binding
     * @param timeout maximum duration of a Nacos read
     * @return a source that owns the newly created ConfigService
     */
    public static NacosConfigSource managed(Properties properties, Duration timeout) {
        return managed(properties, timeout, NacosFactory::createConfigService);
    }

    /**
     * Creates and owns a Nacos client through a replaceable external-client factory.
     *
     * @param properties standard Nacos client properties
     * @param timeout maximum duration of a Nacos read
     * @param factory client creation boundary
     * @return a source that owns the created client
     */
    public static NacosConfigSource managed(
            Properties properties, Duration timeout, NacosConfigServiceFactory factory) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(factory, "factory");
        try {
            return owned(factory.create(properties), timeout);
        } catch (NacosException exception) {
            throw new ConfigSourceException("Unable to create Nacos ConfigService", exception);
        }
    }

    @Override
    public Watch watch(Set<ConfigRef> refs, ConfigSink sink) throws ConfigSourceException {
        Objects.requireNonNull(refs, "refs");
        Objects.requireNonNull(sink, "sink");
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("at least one config reference is required");
        }
        if (closed.get()) {
            throw new IllegalStateException("source is closed");
        }

        var registrations = new ArrayList<Registration>();
        for (var ref : Set.copyOf(refs)) {
            var registration = new Registration(ref, sink);
            // Track before calling Nacos because a failed call may still have registered the
            // listener remotely; cleanup must therefore include the in-flight registration.
            registrations.add(registration);
            try {
                var initial = service.getConfigAndSignListener(
                        ref.dataId(), ref.group(), timeoutMillis, registration.listener);
                registration.initial(initial);
            } catch (NacosException exception) {
                registration.initialUnavailable();
            }
        }

        var watch = new NacosWatch(registrations);
        synchronized (watches) {
            if (closed.get()) {
                watch.close();
                throw new IllegalStateException("source is closed");
            }
            watches.add(watch);
        }
        return watch;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ArrayList<NacosWatch> activeWatches;
        synchronized (watches) {
            activeWatches = new ArrayList<>(watches);
            watches.clear();
        }
        activeWatches.forEach(NacosWatch::close);
        healthChecks.shutdownNow();
        callbacks.shutdownNow();
        if (ownsService) {
            try {
                service.shutDown();
            } catch (NacosException exception) {
                throw new ConfigSourceException("Unable to shut down owned Nacos ConfigService", exception);
            }
        }
    }

    private void recheck() {
        if (closed.get()) {
            return;
        }
        ArrayList<Registration> registrations = new ArrayList<>();
        synchronized (watches) {
            for (var watch : watches) {
                watch.copyActiveRegistrationsTo(registrations);
            }
        }
        if (registrations.isEmpty()) {
            return;
        }
        boolean available;
        try {
            available = "UP".equalsIgnoreCase(service.getServerStatus());
        } catch (RuntimeException exception) {
            available = false;
        }
        if (!available) {
            registrations.forEach(Registration::markUnavailable);
            return;
        }
        registrations.forEach(Registration::recover);
    }

    private final class Registration {
        private final ConfigRef ref;
        private final ConfigSink sink;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final ArrayDeque<SourceEvent> pending = new ArrayDeque<>();
        private boolean initialized;
        private boolean available;
        private boolean registered;
        private long callbacksSeen;
        private final Listener listener = new Listener() {
            @Override
            public java.util.concurrent.Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String content) {
                callback(event(content));
            }
        };

        private Registration(ConfigRef ref, ConfigSink sink) {
            this.ref = ref;
            this.sink = sink;
        }

        private synchronized void initial(String content) {
            if (!active.get() || closed.get()) {
                return;
            }
            // Nacos may race a callback with getConfigAndSignListener's return. Publish the
            // authoritative initial result first, then release callbacks accumulated meanwhile.
            sink.accept(event(content));
            initialized = true;
            available = true;
            registered = true;
            while (!pending.isEmpty()) {
                submit(pending.remove());
            }
        }

        private synchronized void initialUnavailable() {
            if (!active.get() || closed.get()) {
                return;
            }
            sink.accept(new SourceEvent.Unavailable(ref, SourceError.UNAVAILABLE));
            initialized = true;
            available = false;
            while (!pending.isEmpty()) {
                submit(pending.remove());
            }
        }

        private synchronized void callback(SourceEvent event) {
            callbacksSeen++;
            registered = true;
            available = true;
            enqueue(event);
        }

        private synchronized void enqueue(SourceEvent event) {
            if (!active.get() || closed.get()) {
                return;
            }
            if (!initialized) {
                pending.add(event);
                return;
            }
            submit(event);
        }

        private synchronized void markUnavailable() {
            if (!active.get() || closed.get() || !available) {
                return;
            }
            available = false;
            submit(new SourceEvent.Unavailable(ref, SourceError.UNAVAILABLE));
        }

        private void recover() {
            long observedCallbacks;
            boolean listenerRegistered;
            synchronized (this) {
                if (!active.get() || closed.get() || available) {
                    return;
                }
                observedCallbacks = callbacksSeen;
                listenerRegistered = registered;
            }
            try {
                var content = listenerRegistered
                        ? service.getConfig(ref.dataId(), ref.group(), timeoutMillis)
                        : service.getConfigAndSignListener(
                                ref.dataId(), ref.group(), timeoutMillis, listener);
                synchronized (this) {
                    // A callback that arrived during the read is newer than this confirmation and
                    // must win, even if the read began while the source was unavailable.
                    if (active.get() && !closed.get() && !available && callbacksSeen == observedCallbacks) {
                        registered = true;
                        available = true;
                        submit(event(content));
                    }
                }
            } catch (NacosException exception) {
                markUnavailable();
            }
        }

        private void submit(SourceEvent event) {
            callbacks.execute(() -> emit(event));
        }

        private void emit(SourceEvent event) {
            if (active.get() && !closed.get()) {
                sink.accept(event);
            }
        }

        private SourceEvent event(String content) {
            return content == null ? new SourceEvent.Deleted(ref) : new SourceEvent.Value(ref, content);
        }

        private synchronized void close() {
            if (active.compareAndSet(true, false)) {
                pending.clear();
                service.removeListener(ref.dataId(), ref.group(), listener);
            }
        }
    }

    private final class NacosWatch implements Watch {
        private final ArrayList<Registration> registrations;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private NacosWatch(ArrayList<Registration> registrations) {
            this.registrations = registrations;
        }

        private void copyActiveRegistrationsTo(ArrayList<Registration> destination) {
            if (active.get()) {
                destination.addAll(registrations);
            }
        }

        @Override
        public void close() {
            if (!active.compareAndSet(true, false)) {
                return;
            }
            registrations.forEach(Registration::close);
            synchronized (watches) {
                watches.remove(this);
            }
        }
    }
}

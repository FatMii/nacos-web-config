package io.github.fatmii.nacoswebconfig.core;

import java.util.Set;

final class InMemoryConfigSource implements ConfigSource {
    private ConfigSink sink;
    private ConfigSink lastSink;
    private boolean watchClosed;
    private boolean sourceClosed;

    @Override
    public Watch watch(Set<ConfigRef> refs, ConfigSink sink) {
        this.sink = sink;
        this.lastSink = sink;
        refs.forEach(ref -> sink.accept(new SourceEvent.Unavailable(ref, SourceError.UNAVAILABLE)));
        return () -> {
            watchClosed = true;
            this.sink = null;
        };
    }

    void value(ConfigRef ref, String content) {
        sink.accept(new SourceEvent.Value(ref, content));
    }

    void deleted(ConfigRef ref) {
        sink.accept(new SourceEvent.Deleted(ref));
    }

    void unavailable(ConfigRef ref) {
        sink.accept(new SourceEvent.Unavailable(ref, SourceError.UNAVAILABLE));
    }

    void lateValue(ConfigRef ref, String content) {
        lastSink.accept(new SourceEvent.Value(ref, content));
    }

    boolean watchClosed() {
        return watchClosed;
    }

    boolean sourceClosed() {
        return sourceClosed;
    }

    @Override
    public void close() {
        sourceClosed = true;
    }
}

package io.github.fatmii.nacoswebconfig.core;

/** Receives raw configuration facts from a {@link ConfigSource}. */
@FunctionalInterface
public interface ConfigSink {
    /**
     * Delivers one source event.
     *
     * <p>Sources must not assume that this call performs network or browser I/O. The core may invoke
     * downstream subscribers after applying the event, so production adapters should preserve the
     * source ordering contract and avoid sharing mutable event data.
     *
     * @param event immutable event to apply
     */
    void accept(SourceEvent event);
}

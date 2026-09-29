package io.github.fatmii.nacoswebconfig.core;

import java.util.Set;

/**
 * Boundary through which the core watches an external configuration system.
 *
 * <p>An implementation may complete network synchronization after {@link #watch(Set, ConfigSink)}
 * returns, but it must eventually emit one initial {@link SourceEvent.Value}, {@link
 * SourceEvent.Deleted}, or {@link SourceEvent.Unavailable} for every requested reference before
 * emitting later changes for that reference.
 *
 * <p>Events for the same {@link ConfigRef} must be delivered serially. No ordering or transaction
 * relationship is required between different references. Implementations report raw source facts;
 * they must not parse JSON, translate references to exposure aliases, or retain browser-facing
 * state.
 *
 * <p>The source is owned by the {@link ConfigRuntime} passed to {@link ConfigRuntime#start}. Closing
 * that runtime closes both its {@link Watch} and this source.
 */
public interface ConfigSource extends AutoCloseable {
    /**
     * Starts watching exactly the requested references.
     *
     * @param refs immutable set of allowlisted source references
     * @param sink callback that accepts source events
     * @return a handle that stops this registration
     * @throws ConfigSourceException if the watch cannot be established
     */
    Watch watch(Set<ConfigRef> refs, ConfigSink sink) throws ConfigSourceException;

    /** Closes source-owned resources. Repeated calls must be safe. */
    @Override
    void close();
}

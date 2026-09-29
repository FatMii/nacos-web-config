package io.github.fatmii.nacoswebconfig.core;

/** Handle for one {@link ConfigSource} watch registration. */
@FunctionalInterface
public interface Watch extends AutoCloseable {
    /**
     * Stops the registration.
     *
     * <p>Closing is idempotent. After it returns, the source must not begin new callbacks. The core
     * independently rejects callbacks that arrive after its own lifecycle has ended.
     */
    @Override
    void close();
}

package io.github.fatmii.nacoswebconfig.core;

/** Handle for one downstream {@link ConfigSubscriber} registration. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    /** Cancels further delivery. Repeated calls are safe. */
    @Override
    void close();
}

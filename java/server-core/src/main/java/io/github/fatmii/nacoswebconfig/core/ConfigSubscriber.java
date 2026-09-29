package io.github.fatmii.nacoswebconfig.core;

/**
 * Observes immutable snapshots and subsequent changes from a {@link ConfigRuntime}.
 *
 * <p>For each subscription, {@link #onSnapshot(ConfigSnapshot)} is delivered exactly once before
 * any {@link #onChange(ConfigChange)} call. An exception thrown by one subscriber is isolated and
 * does not prevent delivery to other subscribers.
 */
public interface ConfigSubscriber {
    /**
     * Called once with the complete state of the aliases requested by the subscription.
     *
     * @param snapshot immutable initial state
     */
    void onSnapshot(ConfigSnapshot snapshot);

    /**
     * Called when one requested alias transitions to a different observable state.
     *
     * @param change complete new state for the changed alias
     */
    void onChange(ConfigChange change);
}

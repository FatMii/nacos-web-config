package io.github.fatmii.nacoswebconfig.core;

import java.util.Map;

/**
 * Immutable point-in-time view of a requested set of exposure aliases.
 *
 * @param entries entries keyed by logical exposure alias
 */
public record ConfigSnapshot(Map<String, ConfigEntry> entries) {
    /**
     * Defensively copies the supplied map so callers cannot mutate shared runtime state.
     *
     * @param entries entries keyed by logical exposure alias
     */
    public ConfigSnapshot {
        entries = Map.copyOf(entries);
    }
}

package io.github.fatmii.nacoswebconfig.core;

/**
 * One exposure state change delivered after a subscriber's initial snapshot.
 *
 * @param alias logical exposure alias
 * @param entry complete new state for that alias
 */
public record ConfigChange(String alias, ConfigEntry entry) {}

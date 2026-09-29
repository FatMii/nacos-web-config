package io.github.fatmii.nacoswebconfig.core;

/**
 * Immutable server-side state of one exposure.
 *
 * <p>{@code valueJson} and {@code contentHash} describe the same valid value. For {@link
 * ConfigStatus#INVALID} and {@link ConfigStatus#UNAVAILABLE}, they may contain the last-known-good
 * value. For {@link ConfigStatus#DELETED}, both are absent.
 *
 * @param status current state
 * @param valueJson valid JSON object text, or {@code null} when no valid value is retained
 * @param contentHash SHA-256 identity of {@code valueJson}, or {@code null} when no value is present
 * @param errorCode stable reason for an invalid or unavailable state; otherwise {@code null}
 */
public record ConfigEntry(
        ConfigStatus status,
        String valueJson,
        String contentHash,
        ConfigErrorCode errorCode) {

    /**
     * Returns whether this state carries a current or last-known-good value.
     *
     * @return {@code true} when {@link #valueJson()} is present
     */
    public boolean hasValue() {
        return valueJson != null;
    }

    static ConfigEntry unavailable() {
        return new ConfigEntry(ConfigStatus.UNAVAILABLE, null, null, ConfigErrorCode.SOURCE_UNAVAILABLE);
    }
}

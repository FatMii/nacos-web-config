package io.github.fatmii.nacoswebconfig.core;

/** Current server-side state of one exposed configuration. */
public enum ConfigStatus {
    /** The current source content is a valid JSON object. */
    READY,
    /** The latest source content is invalid; a previous valid value may still be available. */
    INVALID,
    /** The source authoritatively deleted the configuration; no retained value remains. */
    DELETED,
    /** The source cannot currently confirm the value; a previous valid value may be retained. */
    UNAVAILABLE
}

package io.github.fatmii.nacoswebconfig.core;

/** Indicates that an external configuration source could not establish or maintain its contract. */
public final class ConfigSourceException extends RuntimeException {
    /**
     * Creates a source exception without exposing source content or credentials.
     *
     * @param message safe diagnostic message
     * @param cause underlying failure
     */
    public ConfigSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}

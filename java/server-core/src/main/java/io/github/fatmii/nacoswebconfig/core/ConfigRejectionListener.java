package io.github.fatmii.nacoswebconfig.core;

/**
 * Observes content the runtime rejected at the browser boundary.
 *
 * <p>Fires after a source value fails validation (non-object JSON or oversize) while the
 * last-known-good entry is retained. The raw content is never passed; only its SHA-256 identity,
 * so operators can correlate the finding with what they published without this library logging
 * potentially sensitive configuration bodies.
 */
@FunctionalInterface
public interface ConfigRejectionListener {
    /**
     * @param alias logical exposure key whose content was rejected
     * @param code stable reason code
     * @param rejectedContentHash {@code sha256:<hex>} of the rejected raw content
     */
    void onRejected(String alias, ConfigErrorCode code, String rejectedContentHash);
}

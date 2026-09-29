package io.github.fatmii.nacoswebconfig.core;

import java.util.Objects;

/**
 * Raw authoritative fact emitted by a {@link ConfigSource} for one {@link ConfigRef}.
 *
 * <p>Deletion and temporary unavailability are intentionally distinct. A network failure must never
 * be represented as deletion, because deletion clears the last-known-good value.
 */
public sealed interface SourceEvent {
    /**
     * Returns the source configuration affected by this event.
     *
     * @return affected source reference
     */
    ConfigRef ref();

    /** Reports the source's current raw content. JSON validation belongs to the core. */
    record Value(ConfigRef ref, String content) implements SourceEvent {
        /**
         * Validates that the source reference and raw content are present.
         *
         * @param ref affected source reference
         * @param content raw source content
         */
        public Value {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(content, "content");
        }
    }

    /** Reports an authoritative deletion. This clears any retained last-known-good value. */
    record Deleted(ConfigRef ref) implements SourceEvent {
        /**
         * Validates that the affected source reference is present.
         *
         * @param ref affected source reference
         */
        public Deleted {
            Objects.requireNonNull(ref, "ref");
        }
    }

    /** Reports that the source cannot currently confirm the value. */
    record Unavailable(ConfigRef ref, SourceError error) implements SourceEvent {
        /**
         * Validates that the affected reference and stable failure category are present.
         *
         * @param ref affected source reference
         * @param error stable failure category
         */
        public Unavailable {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(error, "error");
        }
    }
}

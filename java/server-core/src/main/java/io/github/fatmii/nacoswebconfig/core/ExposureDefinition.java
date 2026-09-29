package io.github.fatmii.nacoswebconfig.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declares one allowlisted configuration that downstream clients may request by logical alias.
 *
 * <p>Aliases are case-sensitive and must match {@code
 * ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$}. A runtime rejects two aliases that point to the same {@link
 * ConfigRef}, because that would make state and authorization ambiguous.
 *
 * @param alias stable downstream-facing name; never a Nacos data identifier
 * @param ref internal source identity
 * @param maxBytes maximum accepted UTF-8 byte length for one source value
 */
public record ExposureDefinition(String alias, ConfigRef ref, int maxBytes) {
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /**
     * Validates the downstream alias, source reference, and positive byte limit.
     *
     * @param alias stable downstream-facing name
     * @param ref internal source identity
     * @param maxBytes maximum accepted UTF-8 byte length
     */
    public ExposureDefinition {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(ref, "ref");
        if (!ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("invalid exposure alias: " + alias);
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
    }
}

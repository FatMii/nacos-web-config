package io.github.fatmii.nacoswebconfig.core;

/**
 * Identifies one configuration in the namespace owned by a {@link ConfigSource}.
 *
 * <p>The runtime deliberately does not expose this identity to browsers. Browser-facing code uses
 * the logical alias from {@link ExposureDefinition} instead.
 *
 * @param group source-specific configuration group
 * @param dataId source-specific configuration data identifier
 */
public record ConfigRef(String group, String dataId) {
    /**
     * Validates that both source identity components contain non-whitespace text.
     *
     * @param group source-specific configuration group
     * @param dataId source-specific configuration data identifier
     */
    public ConfigRef {
        if (isBlank(group) || isBlank(dataId)) {
            throw new IllegalArgumentException("group and dataId must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

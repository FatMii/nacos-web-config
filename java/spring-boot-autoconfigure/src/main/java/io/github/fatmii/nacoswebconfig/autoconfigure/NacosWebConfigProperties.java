package io.github.fatmii.nacoswebconfig.autoconfigure;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** Type-safe configuration contract for the Nacos Web Config Starter. */
@ConfigurationProperties("nacos-web-config")
public record NacosWebConfigProperties(
        boolean enabled,
        @DefaultValue("/_web-config") String path,
        Access access,
        @DefaultValue Source source,
        @DefaultValue Nacos nacos,
        Map<String, Exposure> exposures,
        @DefaultValue Stream stream,
        @DefaultValue Startup startup) {
    private static final Pattern EXPOSURE_ALIAS =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /** Rejects an enabled module unless its public surface is explicit and valid. */
    public NacosWebConfigProperties {
        if (enabled && access == null) {
            throw new IllegalArgumentException(
                    "nacos-web-config.access must be explicitly configured as public or authenticated");
        }
        if (enabled
                && (path == null
                        || !path.startsWith("/")
                        || path.length() < 2
                        || path.endsWith("/"))) {
            throw new IllegalArgumentException(
                    "nacos-web-config.path must start with '/' and must not end with '/'");
        }
        if (enabled && (exposures == null || exposures.isEmpty())) {
            throw new IllegalArgumentException(
                    "nacos-web-config.exposures must contain at least one entry");
        }
        if (enabled && stream.maxKeysPerConnection() < 1) {
            throw new IllegalArgumentException(
                    "nacos-web-config.stream.max-keys-per-connection must be positive");
        }
        if (enabled && (stream.heartbeat().isZero() || stream.heartbeat().isNegative())) {
            throw new IllegalArgumentException(
                    "nacos-web-config.stream.heartbeat must be positive");
        }
        if (enabled
                && (stream.connectionTimeout().isZero()
                        || stream.connectionTimeout().isNegative())) {
            throw new IllegalArgumentException(
                    "nacos-web-config.stream.connection-timeout must be positive");
        }
        if (enabled && stream.maxConnections() < 1) {
            throw new IllegalArgumentException(
                    "nacos-web-config.stream.max-connections must be positive");
        }
        if (enabled && stream.maxPendingBytesPerConnection().toBytes() < 1) {
            throw new IllegalArgumentException(
                    "nacos-web-config.stream.max-pending-bytes-per-connection must be positive");
        }
        if (enabled
                && source.mode() == SourceMode.MANAGED
                && nacos.serverAddr().isBlank()) {
            throw new IllegalArgumentException(
                    "nacos-web-config.nacos.server-addr is required for source.mode=managed");
        }
        if (enabled
                && source.mode() == SourceMode.BEAN
                && source.beanName().isBlank()) {
            throw new IllegalArgumentException(
                    "nacos-web-config.source.bean-name is required for source.mode=bean");
        }
        if (enabled) {
            for (String alias : exposures.keySet()) {
                var exposure = exposures.get(alias);
                if (!EXPOSURE_ALIAS.matcher(alias).matches()) {
                    throw new IllegalArgumentException(
                            "invalid nacos-web-config exposure alias: " + alias);
                }
                if (exposure.dataId() == null || exposure.dataId().isBlank()) {
                    throw new IllegalArgumentException(
                            "nacos-web-config.exposures."
                                    + alias
                                    + ".data-id must not be blank");
                }
                if (exposure.group() == null || exposure.group().isBlank()) {
                    throw new IllegalArgumentException(
                            "nacos-web-config.exposures."
                                    + alias
                                    + ".group must not be blank");
                }
                if (exposure.maxBytes() < 1) {
                    throw new IllegalArgumentException(
                            "nacos-web-config.exposures."
                                    + alias
                                    + ".max-bytes must be positive");
                }
            }
        }
    }

    /** Selects the ownership strategy for the Nacos ConfigService. */
    public record Source(
            @DefaultValue("auto") SourceMode mode,
            @DefaultValue("") String beanName) {}

    /** Connection settings for the single Nacos namespace used by this runtime. */
    public record Nacos(
            @DefaultValue("") String serverAddr,
            @DefaultValue("public") String namespace,
            @DefaultValue("") String username,
            @DefaultValue("") String password,
            @DefaultValue("3s") Duration timeout) {}

    /** Maps one public logical key to one fixed Nacos group and dataId. */
    public record Exposure(
            String dataId,
            @DefaultValue("DEFAULT_GROUP") String group,
            @DefaultValue("65536") int maxBytes) {}

    /** Controls SSE connection lifetime and resource limits. */
    public record Stream(
            @DefaultValue("15s") Duration heartbeat,
            @DefaultValue("30m") Duration connectionTimeout,
            @DefaultValue("1000") int maxConnections,
            @DefaultValue("32") int maxKeysPerConnection,
            @DefaultValue("1MB") DataSize maxPendingBytesPerConnection) {}

    /** Controls how source availability affects application startup. */
    public record Startup(@DefaultValue("false") boolean failFast) {}
}

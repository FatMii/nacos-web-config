package io.github.fatmii.nacoswebconfig.core;

/** Stable, content-safe error codes suitable for downstream protocol adapters. */
public enum ConfigErrorCode {
    /** The value is not valid JSON or its top-level value is not an object. */
    INVALID_JSON,
    /** The UTF-8 representation exceeds the exposure's configured byte limit. */
    TOO_LARGE,
    /** The external source cannot currently confirm the authoritative value. */
    SOURCE_UNAVAILABLE
}

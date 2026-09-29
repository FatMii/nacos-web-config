package io.github.fatmii.nacoswebconfig.autoconfigure;

/** Selects whether the Starter creates or reuses the Nacos client. */
public enum SourceMode {
    AUTO,
    MANAGED,
    BEAN
}

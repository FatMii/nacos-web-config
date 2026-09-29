package io.github.fatmii.nacoswebconfig.mvc;

final class WebConfigRequestException extends RuntimeException {
    private final String code;

    private WebConfigRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    static WebConfigRequestException invalid() {
        return new WebConfigRequestException("INVALID_REQUEST", "Invalid stream request");
    }

    static WebConfigRequestException unknownKey() {
        return new WebConfigRequestException(
                "UNKNOWN_KEY", "One or more requested keys are unavailable");
    }

    static WebConfigRequestException connectionLimit() {
        return new WebConfigRequestException(
                "CONNECTION_LIMIT", "Stream connection limit reached");
    }

    static WebConfigRequestException moduleStopped() {
        return new WebConfigRequestException(
                "MODULE_STOPPED", "Web config streaming is unavailable");
    }

    static WebConfigRequestException unauthenticated() {
        return new WebConfigRequestException(
                "UNAUTHENTICATED", "Authentication is required");
    }

    String code() {
        return code;
    }
}

package io.github.fatmii.nacoswebconfig.mvc;

import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/** Shared detection for ordinary SSE / HTTP client disconnects. */
final class BenignDisconnects {
    private BenignDisconnects() {}

    static boolean isBenign(Throwable error) {
        for (var current = error; current != null; current = current.getCause()) {
            if (current instanceof AsyncRequestNotUsableException) {
                return true;
            }
            var name = current.getClass().getName();
            if (name.endsWith("ClientAbortException")) {
                return true;
            }
            if (messageLooksLikeDisconnect(current.getMessage())) {
                return true;
            }
        }
        return false;
    }

    static boolean messageLooksLikeDisconnect(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        var lower = message.toLowerCase();
        return lower.contains("broken pipe")
                || lower.contains("connection reset")
                || lower.contains("async request not usable")
                || lower.contains("clientabortexception")
                || lower.contains("connection was aborted")
                || lower.contains("forcibly closed")
                || message.contains("中止了一个已建立的连接")
                || message.contains("连接被对方重设")
                || message.contains("你的主机中的软件中止");
    }
}

package io.github.fatmii.nacoswebconfig.mvc;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/**
 * Drops ERROR/WARN noise from Tomcat/Spring when an SSE client disconnects mid-write. Ordinary
 * browser close/refresh must not look like an application failure in host logs.
 */
final class BenignDisconnectLogFilter extends TurboFilter {
    static void installIfLogbackPresent() {
        try {
            var factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext context)) {
                return;
            }
            for (var existing : context.getTurboFilterList()) {
                if (existing instanceof BenignDisconnectLogFilter) {
                    return;
                }
            }
            var filter = new BenignDisconnectLogFilter();
            filter.setName("nacosWebConfigBenignDisconnect");
            filter.start();
            context.addTurboFilter(filter);
        } catch (NoClassDefFoundError ignored) {
            // Logback is optional; other SLF4J bindings simply keep default logging.
        }
    }

    @Override
    public FilterReply decide(
            Marker marker,
            Logger logger,
            Level level,
            String format,
            Object[] params,
            Throwable throwable) {
        if (level == null || !level.isGreaterOrEqual(Level.WARN)) {
            return FilterReply.NEUTRAL;
        }
        if (BenignDisconnects.isBenign(throwable)) {
            return FilterReply.DENY;
        }
        if (format != null && BenignDisconnects.messageLooksLikeDisconnect(format)) {
            return FilterReply.DENY;
        }
        if (logger != null
                && logger.getName().contains("dispatcherServlet")
                && BenignDisconnects.isBenign(throwable)) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}

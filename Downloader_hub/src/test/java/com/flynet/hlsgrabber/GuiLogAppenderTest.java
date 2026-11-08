package com.flynet.hlsgrabber;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import com.flynet.hlsgrabber.ui.fx.GuiLogAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GuiLogAppender contract against the real logback.xml wiring: the GUI appender
 * is configured, formatted lines reach the installed sink while it runs, and the
 * sink stays silent after the appender stops (shutdown path). Headless, no FX toolkit.
 */
class GuiLogAppenderTest {

    private final List<String> lines = new ArrayList<>();

    @AfterEach
    void detach() {
        GuiLogAppender.setSink(null);
    }

    @Test
    void appendsFormattedLinesToSinkWhileStarted() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        GuiLogAppender app = null;
        Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it = root.iteratorForAppenders();
        while (it.hasNext()) {
            Appender<ch.qos.logback.classic.spi.ILoggingEvent> a = it.next();
            if (a instanceof GuiLogAppender g) {
                app = g;
                break;
            }
        }
        assertNotNull(app, "logback.xml must configure GuiLogAppender as a root appender");

        GuiLogAppender.setSink(lines::add);
        org.slf4j.Logger log = LoggerFactory.getLogger("gui-appender-test");
        log.info("ffmpeg: 6.0");
        assertEquals(1, lines.size(), "one line expected while started, got " + lines);
        String line = lines.get(0);
        assertTrue(line.contains("INFO"), "level token missing: " + line);
        assertTrue(line.contains("ffmpeg: 6.0"), "message missing: " + line);

        app.stop();
        log.info("after stop");
        assertEquals(1, lines.size(), "sink must stay silent after appender stop");

        app.start();
        GuiLogAppender.setSink(null);
    }
}

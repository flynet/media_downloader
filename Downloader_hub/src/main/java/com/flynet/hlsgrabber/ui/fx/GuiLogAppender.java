package com.flynet.hlsgrabber.ui.fx;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Logback appender feeding the JavaFX log panel.
 *
 * <p>Logback worker threads push lines into a static sink; the FX panel
 * installs the sink on start and marshals to the FX app thread itself.
 * Before the sink is installed, lines are dropped (the console appender
 * still mirrors everything to stdout for the packaged app).
 */
public final class GuiLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static volatile Consumer<String> sink;

    /** Install the UI sink (nullable to detach). */
    public static void setSink(Consumer<String> s) {
        sink = s;
    }

    @Override
    protected void append(ILoggingEvent e) {
        Consumer<String> s = sink;
        if (s == null) return;
        String msg = e.getFormattedMessage();
        if (msg.contains("\r")) msg = msg.replace("\r", " ");
        if (msg.contains("\n")) msg = msg.replace("\n", " ");
        String line = TIME.format(Instant.ofEpochMilli(e.getTimeStamp()))
                + "  " + String.format("%-5s", e.getLevel().toString()) + "  " + msg;
        s.accept(line);
    }
}

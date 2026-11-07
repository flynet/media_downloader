package com.example.hlsgrabber.ui;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Logback-аппендер, который стримит строки лога в GUI-панель консоли (TZ 5.2, 6.7).
 * Сигун статический: logback создаёт инстанс аппендера при инициализации конфига,
 * App привязывает сигун к LogPanel сразу после выбора папки загрузок.
 * Logback appender that streams log lines into the GUI console panel (TZ 5.2, 6.7).
 * The sink is static: logback creates the appender instance on config init, App wires
 * the sink to LogPanel right after the downloads folder is chosen.
 */
public final class SwingLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static volatile Consumer<String> sink = line -> { };

    public static void setSink(Consumer<String> c) {
        sink = c;
    }

    @Override
    protected void append(ILoggingEvent event) {
        Consumer<String> s = sink;
        String line = LocalTime.now().format(FMT) + "  " + event.getLevel()
                + "  " + event.getFormattedMessage();
        s.accept(line);
    }
}

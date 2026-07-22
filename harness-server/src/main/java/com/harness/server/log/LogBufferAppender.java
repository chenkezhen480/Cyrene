package com.harness.server.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Logback appender that buffers WARN/ERROR events in memory.
 * Events are drained by LogStorageService on a schedule (every 1h) and on shutdown.
 * <p>
 * This is a non-blocking appender — it never writes to disk directly,
 * only enqueues to a lock-free queue. The actual I/O happens on the storage thread.
 */
public class LogBufferAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final ConcurrentLinkedQueue<ILoggingEvent> buffer = new ConcurrentLinkedQueue<>();

    @Override
    protected void append(ILoggingEvent event) {
        if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
            buffer.add(event);
        }
    }

    /**
     * Drain all buffered events. Returns empty list if nothing buffered.
     * Thread-safe: multiple drains won't duplicate events.
     */
    public static List<ILoggingEvent> drain() {
        List<ILoggingEvent> events = new ArrayList<>();
        ILoggingEvent event;
        while ((event = buffer.poll()) != null) {
            events.add(event);
        }
        return events;
    }

    /**
     * Current buffer size (approximate, for diagnostics).
     */
    public static int bufferSize() {
        return buffer.size();
    }
}

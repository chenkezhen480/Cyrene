package com.harness.server.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically drains WARN/ERROR log events from {@link LogBufferAppender}
 * and writes them to local files. Also registers a shutdown hook for final flush.
 * <p>
 * Storage: {@code {logDir}/warn-errors-{date}.log}
 * Retention: files older than {@code HARNESS_LOG_RETENTION_DAYS} (default 7) are auto-deleted.
 */
public class LogStorageService {

    private static final Logger log = LoggerFactory.getLogger(LogStorageService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Path logDir;
    private final int retentionDays;
    private final ScheduledExecutorService scheduler;

    public LogStorageService() {
        EnvConfig cfg = EnvConfig.get();
        this.logDir = Path.of(cfg.getString(EnvKey.LOG_STORAGE_DIR, "./logs"));
        this.retentionDays = cfg.getInt(EnvKey.LOG_RETENTION_DAYS, 7);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-storage");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start periodic flush and register shutdown hook.
     */
    public void start() {
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            log.warn("[LogStorage] Failed to create log dir {}: {}", logDir, e.getMessage());
            return;
        }

        // Flush every 1 hour
        long intervalMs = Duration.ofHours(1).toMillis();
        scheduler.scheduleAtFixedRate(this::flush, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        // Shutdown hook: final flush
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "log-storage-shutdown"));

        log.info("[LogStorage] Started: dir={}, retentionDays={}, flushInterval=1h", logDir, retentionDays);
    }

    /**
     * Drain buffered events and write to file. Safe to call multiple times.
     */
    public void flush() {
        List<ILoggingEvent> events = LogBufferAppender.drain();
        if (!events.isEmpty()) {
            writeToFile(events);
        }
        // Always cleanup old files, even if no new events
        cleanupOldFiles();
    }

    private void writeToFile(List<ILoggingEvent> events) {
        Path file = logDir.resolve("warn-errors-" + LocalDate.now().format(DATE_FMT) + ".log");
        try {
            StringBuilder sb = new StringBuilder();
            for (ILoggingEvent e : events) {
                sb.append(TS_FMT.format(LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(e.getTimeStamp()),
                                java.time.ZoneId.systemDefault())))
                  .append(" ").append(e.getLevel())
                  .append(" ").append(e.getLoggerName())
                  .append(" - ").append(e.getFormattedMessage())
                  .append('\n');

                IThrowableProxy tp = e.getThrowableProxy();
                if (tp != null) {
                    appendThrowable(sb, tp, "");
                }
            }

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("[LogStorage] Flushed {} events to {}", events.size(), file.getFileName());
        } catch (IOException e) {
            log.warn("[LogStorage] Failed to write {}: {}", file, e.getMessage());
        }
    }

    private void appendThrowable(StringBuilder sb, IThrowableProxy tp, String indent) {
        if (tp == null) return;
        sb.append(indent).append(tp.getClassName()).append(": ").append(tp.getMessage()).append('\n');
        if (tp.getStackTraceElementProxyArray() != null) {
            for (var ste : tp.getStackTraceElementProxyArray()) {
                sb.append(indent).append("\tat ").append(ste.toString()).append('\n');
            }
        }
        IThrowableProxy[] suppressed = tp.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy s : suppressed) {
                sb.append(indent).append("Suppressed: ");
                appendThrowable(sb, s, indent + "\t");
            }
        }
        appendThrowable(sb, tp.getCause(), indent);
    }

    private void cleanupOldFiles() {
        try {
            LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
            try (var stream = Files.list(logDir)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("warn-errors-") && name.endsWith(".log");
                }).forEach(p -> {
                    try {
                        String dateStr = p.getFileName().toString()
                                .replace("warn-errors-", "").replace(".log", "");
                        LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
                        if (date.isBefore(cutoff)) {
                            Files.delete(p);
                            log.info("[LogStorage] Deleted old log: {}", p.getFileName());
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Stop the scheduler (for testing or graceful shutdown).
     */
    public void stop() {
        scheduler.shutdown();
    }
}

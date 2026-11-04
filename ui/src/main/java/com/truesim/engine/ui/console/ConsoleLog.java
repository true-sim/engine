package com.truesim.engine.ui.console;

import com.truesim.annotations.AllowAllocation;
import com.truesim.ui.console.LogInbox;
import com.truesim.ui.console.LogLevel;

/**
 * Where log records go on their way to the console (design 5.10, UI28): one process-wide {@link LogInbox} that
 * {@link ConsoleLogProvider}'s loggers write from any thread and the console drains once a frame, from the first log
 * line of the process on (records before the console exists wait there, the oldest dropped past its capacity). Also
 * what the provider captures: records at {@link #captureLevel} or above, with their source ({@code File.java:42})
 * looked up for those at {@link #sourceLevel} or above (a stack walk: costly, so warnings and errors by default). The
 * plugin's settings {@code ui.console.captureLevel} and {@code ui.console.sourceLevel} set both.
 */
public final class ConsoleLog {

    /** The records waiting for the console: room for 4,096, the oldest dropped. */
    private static final LogInbox INBOX = new LogInbox(4096);
    private static volatile int captureLevel = LogLevel.INFO;
    private static volatile int sourceLevel = LogLevel.WARN;

    private ConsoleLog() {
    }

    /** The process's inbox. */
    public static LogInbox inbox() {
        return INBOX;
    }

    /** The lowest level captured for the console ({@link LogLevel}); ERROR + 1 captures nothing. */
    public static int captureLevel() {
        return captureLevel;
    }

    /** Captures records at {@code level} or above. */
    public static void captureLevel(int level) {
        captureLevel = level;
    }

    /** The lowest level whose records get their source file and line. */
    public static int sourceLevel() {
        return sourceLevel;
    }

    /** Looks up the source of records at {@code level} or above (ERROR + 1: never). */
    public static void sourceLevel(int level) {
        sourceLevel = level;
    }

    /**
     * Logs a preformatted record into the inbox if its level is captured, its source looked up from the caller's
     * stack past frames of classes whose names start with {@code skip} (the logging API's).
     */
    @AllowAllocation("a log record's source, when looked up")
    public static void log(int level, String logger, String message, long millis, String skip) {
        if (level < captureLevel) {
            return;
        }
        String where = level >= sourceLevel ? source(skip) : null;
        INBOX.log(level, logger, message, where, millis);
    }

    /** The first frame of the calling stack outside the logging classes, as {@code File.java:line}, or null. */
    @AllowAllocation("a stack walk")
    static String source(String skip) {
        return StackWalker.getInstance().walk(frames -> frames
            .filter(f -> !f.getClassName().startsWith(skip) && !f.getClassName().startsWith("org.slf4j.")
                && !f.getClassName().equals(ConsoleLog.class.getName()))
            .findFirst()
            .map(f -> f.getFileName() == null ? null : f.getFileName() + ":" + f.getLineNumber())
            .orElse(null));
    }
}

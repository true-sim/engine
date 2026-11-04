package com.truesim.engine.ui.console;

import com.truesim.ui.console.LogLevel;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NOPLogger;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.CallerBoundaryAware;
import org.slf4j.spi.LoggingEventBuilder;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * An SLF4J 2 provider that feeds the developer console (design 5.10, UI28) and passes everything on to the binding
 * the app has (logback, say): each logger writes the records {@link ConsoleLog} captures, formatted, into the console's
 * {@link ConsoleLog#inbox() inbox} (from any thread), and hands every call to the other provider's logger of the same
 * name, unchanged, with this logger as the caller boundary so the other binding finds the real caller.
 *
 * <p>Chosen explicitly, not by service discovery (which would race the app's binding):
 * {@code -Dslf4j.provider=com.truesim.engine.ui.console.ConsoleLogProvider} on the command line, or the system property
 * set before the first logger is made. The other provider is the first one {@link ServiceLoader} finds besides this
 * one; without one, records go to the console only.
 */
public final class ConsoleLogProvider implements SLF4JServiceProvider {

    /** The API version this provider implements. */
    public static final String API_VERSION = "2.0.99";

    private SLF4JServiceProvider delegate;
    private ILoggerFactory factory;
    private IMarkerFactory markers;
    private MDCAdapter mdc;

    @Override
    public void initialize() {
        for (SLF4JServiceProvider other : ServiceLoader.load(SLF4JServiceProvider.class,
            ConsoleLogProvider.class.getClassLoader())) {
            if (!(other instanceof ConsoleLogProvider)) {
                delegate = other;
                break;
            }
        }
        initialize(delegate);
    }

    /** Initializes over {@code other} (initialized here), or alone with null. */
    void initialize(SLF4JServiceProvider other) {
        delegate = other;
        ILoggerFactory next;
        if (other != null) {
            other.initialize();
            next = other.getLoggerFactory();
            markers = other.getMarkerFactory();
            mdc = other.getMDCAdapter();
        } else {
            next = name -> NOPLogger.NOP_LOGGER;
            markers = new BasicMarkerFactory();
            mdc = new NOPMDCAdapter();
        }
        ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();
        factory = name -> loggers.computeIfAbsent(name, n -> new ConsoleLogger(n, next.getLogger(n)));
    }

    /** The provider records are passed on to, or null. */
    public SLF4JServiceProvider delegate() {
        return delegate;
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return factory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markers;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdc;
    }

    @Override
    public String getRequestedApiVersion() {
        return API_VERSION;
    }

    /** The console's level of an SLF4J level. */
    static int level(Level level) {
        return switch (level) {
            case TRACE -> LogLevel.TRACE;
            case DEBUG -> LogLevel.DEBUG;
            case INFO -> LogLevel.INFO;
            case WARN -> LogLevel.WARN;
            case ERROR -> LogLevel.ERROR;
        };
    }

    /** A logger writing to the console's inbox and to the other binding's logger. */
    static final class ConsoleLogger extends LegacyAbstractLogger {

        private static final long serialVersionUID = 1;
        private static final String FQCN = ConsoleLogger.class.getName();
        private final transient Logger next;

        ConsoleLogger(String name, Logger next) {
            this.name = name;
            this.next = next;
        }

        private static boolean captured(int level) {
            return level >= ConsoleLog.captureLevel();
        }

        @Override
        public boolean isTraceEnabled() {
            return captured(LogLevel.TRACE) || next.isTraceEnabled();
        }

        @Override
        public boolean isDebugEnabled() {
            return captured(LogLevel.DEBUG) || next.isDebugEnabled();
        }

        @Override
        public boolean isInfoEnabled() {
            return captured(LogLevel.INFO) || next.isInfoEnabled();
        }

        @Override
        public boolean isWarnEnabled() {
            return captured(LogLevel.WARN) || next.isWarnEnabled();
        }

        @Override
        public boolean isErrorEnabled() {
            return captured(LogLevel.ERROR) || next.isErrorEnabled();
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return FQCN;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern, Object[] arguments,
            Throwable throwable) {
            int console = level(level);
            if (captured(console)) {
                String text = MessageFormatter.basicArrayFormat(pattern, arguments);
                if (throwable != null) {
                    text = text + " (" + throwable + ")";
                }
                ConsoleLog.log(console, name, text, System.currentTimeMillis(), FQCN);
            }
            if (next.isEnabledForLevel(level)) {
                LoggingEventBuilder event = next.atLevel(level);
                if (event instanceof CallerBoundaryAware boundary) {
                    boundary.setCallerBoundary(FQCN);
                }
                if (marker != null) {
                    event.addMarker(marker);
                }
                if (arguments != null) {
                    for (Object argument : arguments) {
                        event.addArgument(argument);
                    }
                }
                event.setCause(throwable).log(pattern);
            }
        }
    }
}

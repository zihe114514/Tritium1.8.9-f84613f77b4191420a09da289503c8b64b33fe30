package tritium.utils.logging;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MessageFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

@Getter
public class Logger implements org.apache.logging.log4j.Logger {

    private final String name;

    @Getter
    @Setter
    private LogLevel overrideLevel = null;

    public Logger() {
        this(Thread.currentThread().getName());
    }

    @SneakyThrows
    public Logger(String name) {
        this.name = name;
    }

    public void debug(String message, Object... objects) {
        LogManager.print(this, LogLevel.DEBUG, LogManager.parse(message, objects), name);
    }

    public void info(String message, Object... objects) {
        LogManager.print(this, LogLevel.INFO, LogManager.parse(message, objects), name);
    }

    public void warn(String message, Object... objects) {
        LogManager.print(this, LogLevel.WARN, LogManager.parse(message, objects), name);
    }

    public void error(Throwable t) {
        this.error(t.getMessage(), t);
    }

    public void error(String message, Object... objects) {
        LogManager.print(this, LogLevel.ERROR, LogManager.parse(message, objects), name);
    }

    public void fatal(Throwable t) {
        this.fatal(t.getMessage(), t);
    }

    public void fatal(String message, Object... objects) {
        LogManager.print(this, LogLevel.CRITICAL, LogManager.parse(message, objects), name);
    }

    public boolean isDebugEnabled() {
        return LogManager.getDefaultLogLevel().getLevel() >= LogLevel.DEBUG.getLevel();
    }

    private final StringWriter stringWriter = new StringWriter();
    private final PrintWriter writer = new PrintWriter(stringWriter);

    public String getErrorStackTrace(Throwable t) {
        stringWriter.getBuffer().delete(0, stringWriter.getBuffer().length());
        t.printStackTrace(writer);
        writer.flush();

        return stringWriter.toString();
    }

    @Override
    public void catching(Level level, Throwable throwable) {
        LogManager.print(this, getLogLevel(level), getErrorStackTrace(throwable), name);
    }

    @Override
    public void catching(Throwable throwable) {
        LogManager.print(this, LogLevel.ERROR, getErrorStackTrace(throwable), name);
    }

    @Override
    public void debug(Marker marker, Message message) {
        debug(message.getFormattedMessage());
    }

    @Override
    public void debug(Marker marker, Message message, Throwable throwable) {
        debug(message.getFormattedMessage(), throwable);
    }

    @Override
    public void debug(Marker marker, Object o) {
        debug(String.valueOf(o));
    }

    @Override
    public void debug(Marker marker, Object o, Throwable throwable) {
        debug(String.valueOf(o), throwable);
    }

    @Override
    public void debug(Marker marker, String s) {
        debug(s);
    }

    @Override
    public void debug(Marker marker, String s, Object... objects) {
        debug(s, objects);
    }

    @Override
    public void debug(Marker marker, String s, Throwable throwable) {
        debug(s, throwable);
    }

    @Override
    public void debug(Message message) {
        debug(message.getFormattedMessage());
    }

    @Override
    public void debug(Message message, Throwable throwable) {
        debug(message.getFormattedMessage(), throwable);
    }

    @Override
    public void debug(Object o) {
        debug(String.valueOf(o));
    }

    @Override
    public void debug(Object o, Throwable throwable) {
        debug(String.valueOf(o), throwable);
    }

    @Override
    public void debug(String s) {
        debug(s, new Object[]{});
    }

    @Override
    public void debug(String s, Throwable throwable) {
        debug(s, new Object[]{throwable});
    }

    @Override
    public void entry() {
        debug("entry");
    }

    @Override
    public void entry(Object... objects) {
        StringBuilder sb = new StringBuilder("entry params(");
        for (int i = 0; i < objects.length; i++) {
            sb.append(objects[i]);
            if (i < objects.length - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        debug(sb.toString());
    }

    @Override
    public void error(Marker marker, Message message) {
        error(message.getFormattedMessage());
    }

    @Override
    public void error(Marker marker, Message message, Throwable throwable) {
        error(message.getFormattedMessage(), throwable);
    }

    @Override
    public void error(Marker marker, Object o) {
        error(String.valueOf(o));
    }

    @Override
    public void error(Marker marker, Object o, Throwable throwable) {
        error(String.valueOf(o), throwable);
    }

    @Override
    public void error(Marker marker, String s) {
        error(s);
    }

    @Override
    public void error(Marker marker, String s, Object... objects) {
        error(s, objects);
    }

    @Override
    public void error(Marker marker, String s, Throwable throwable) {
        error(s, throwable);
    }

    @Override
    public void error(Message message) {
        error(message.getFormattedMessage());
    }

    @Override
    public void error(Message message, Throwable throwable) {
        error(message.getFormattedMessage(), throwable);
    }

    @Override
    public void error(Object o) {
        error(String.valueOf(o));
    }

    @Override
    public void error(Object o, Throwable throwable) {
        error(String.valueOf(o), throwable);
    }

    @Override
    public void error(String s) {
        error(s, new Object[]{});
    }

    @Override
    public void error(String s, Throwable throwable) {
        error(s, new Object[]{throwable});
    }

    @Override
    public void exit() {
        debug("exit");
    }

    @Override
    public <R> R exit(R r) {
        debug("exit");
        return r;
    }

    @Override
    public void fatal(Marker marker, Message message) {
        fatal(message.getFormattedMessage());
    }

    @Override
    public void fatal(Marker marker, Message message, Throwable throwable) {
        fatal(message.getFormattedMessage(), throwable);
    }

    @Override
    public void fatal(Marker marker, Object o) {
        fatal(String.valueOf(o));
    }

    @Override
    public void fatal(Marker marker, Object o, Throwable throwable) {
        fatal(String.valueOf(o), throwable);
    }

    @Override
    public void fatal(Marker marker, String s) {
        fatal(s);
    }

    @Override
    public void fatal(Marker marker, String s, Object... objects) {
        fatal(s, objects);
    }

    @Override
    public void fatal(Marker marker, String s, Throwable throwable) {
        fatal(s, throwable);
    }

    @Override
    public void fatal(Message message) {
        fatal(message.getFormattedMessage());
    }

    @Override
    public void fatal(Message message, Throwable throwable) {
        fatal(message.getFormattedMessage(), throwable);
    }

    @Override
    public void fatal(Object o) {
        fatal(String.valueOf(o));
    }

    @Override
    public void fatal(Object o, Throwable throwable) {
        fatal(String.valueOf(o), throwable);
    }

    @Override
    public void fatal(String s) {
        fatal(s, new Object[]{});
    }

    @Override
    public void fatal(String s, Throwable throwable) {
        fatal(s, new Object[]{throwable});
    }

    @Override
    public MessageFactory getMessageFactory() {
        return null;
    }

    @Override
    public void info(Marker marker, Message message) {
        info(message.getFormattedMessage());
    }

    @Override
    public void info(Marker marker, Message message, Throwable throwable) {
        info(message.getFormattedMessage(), throwable);
    }

    @Override
    public void info(Marker marker, Object o) {
        info(String.valueOf(o));
    }

    @Override
    public void info(Marker marker, Object o, Throwable throwable) {
        info(String.valueOf(o), throwable);
    }

    @Override
    public void info(Marker marker, String s) {
        info(s);
    }

    @Override
    public void info(Marker marker, String s, Object... objects) {
        info(s, objects);
    }

    @Override
    public void info(Marker marker, String s, Throwable throwable) {
        info(s, throwable);
    }

    @Override
    public void info(Message message) {
        info(message.getFormattedMessage());
    }

    @Override
    public void info(Message message, Throwable throwable) {
        info(message.getFormattedMessage(), throwable);
    }

    @Override
    public void info(Object o) {
        info(String.valueOf(o));
    }

    @Override
    public void info(Object o, Throwable throwable) {
        info(String.valueOf(o), throwable);
    }

    @Override
    public void info(String s) {
        info(s, new Object[]{});
    }

    @Override
    public void info(String s, Throwable throwable) {
        info(s, new Object[]{throwable});
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return isDebugEnabled();
    }

    @Override
    public boolean isEnabled(Level level) {
        LogLevel logLevel = getLogLevel(level);
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= logLevel.getLevel();
    }

    @Override
    public boolean isEnabled(Level level, Marker marker) {
        return isEnabled(level);
    }

    @Override
    public boolean isErrorEnabled() {
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= LogLevel.ERROR.getLevel();
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return isErrorEnabled();
    }

    @Override
    public boolean isFatalEnabled() {
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= LogLevel.CRITICAL.getLevel();
    }

    @Override
    public boolean isFatalEnabled(Marker marker) {
        return isFatalEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= LogLevel.INFO.getLevel();
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return false; // Trace level not implemented in this logging system
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return isTraceEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
        return (overrideLevel != null ? overrideLevel : LogManager.getDefaultLogLevel()).getLevel() >= LogLevel.WARN.getLevel();
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return isWarnEnabled();
    }

    @Override
    public void log(Level level, Marker marker, Message message) {
        log(level, message.getFormattedMessage());
    }

    @Override
    public void log(Level level, Marker marker, Message message, Throwable throwable) {
        log(level, message.getFormattedMessage(), throwable);
    }

    @Override
    public void log(Level level, Marker marker, Object o) {
        log(level, String.valueOf(o));
    }

    @Override
    public void log(Level level, Marker marker, Object o, Throwable throwable) {
        log(level, String.valueOf(o), throwable);
    }

    @Override
    public void log(Level level, Marker marker, String s) {
        log(level, s);
    }

    @Override
    public void log(Level level, Marker marker, String s, Object... objects) {
        log(level, s, objects);
    }

    @Override
    public void log(Level level, Marker marker, String s, Throwable throwable) {
        log(level, s, throwable);
    }

    @Override
    public void log(Level level, Message message) {
        log(level, message.getFormattedMessage());
    }

    @Override
    public void log(Level level, Message message, Throwable throwable) {
        log(level, message.getFormattedMessage(), throwable);
    }

    @Override
    public void log(Level level, Object o) {
        log(level, String.valueOf(o));
    }

    @Override
    public void log(Level level, Object o, Throwable throwable) {
        log(level, String.valueOf(o), throwable);
    }

    @Override
    public void log(Level level, String s) {
        log(level, s, new Object[]{});
    }

    @Override
    public void log(Level level, String s, Object... objects) {
        LogLevel logLevel = getLogLevel(level);
        LogManager.print(this, logLevel, LogManager.parse(s, objects), name);
    }

    @Override
    public void log(Level level, String s, Throwable throwable) {
        log(level, s, new Object[]{throwable});
    }

    @Override
    public void printf(Level level, Marker marker, String s, Object... objects) {
        log(level, s, objects);
    }

    @Override
    public void printf(Level level, String s, Object... objects) {
        log(level, s, objects);
    }

    @Override
    public <T extends Throwable> T throwing(Level level, T t) {
        LogManager.print(this, getLogLevel(level), getErrorStackTrace(t), name);
        return t;
    }

    @Override
    public <T extends Throwable> T throwing(T t) {
        LogManager.print(this, LogLevel.ERROR, getErrorStackTrace(t), name);
        return t;
    }

    @Override
    public void trace(Marker marker, Message message) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Marker marker, Message message, Throwable throwable) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Marker marker, Object o) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Marker marker, Object o, Throwable throwable) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Marker marker, String s) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Marker marker, String s, Object... objects) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Marker marker, String s, Throwable throwable) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Message message) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Message message, Throwable throwable) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Object o) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(Object o, Throwable throwable) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(String s) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(String s, Object... objects) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void trace(String s, Throwable throwable) {
        // Trace level not implemented in this logging system
    }

    @Override
    public void warn(Marker marker, Message message) {
        warn(message.getFormattedMessage());
    }

    @Override
    public void warn(Marker marker, Message message, Throwable throwable) {
        warn(message.getFormattedMessage(), throwable);
    }

    @Override
    public void warn(Marker marker, Object o) {
        warn(String.valueOf(o));
    }

    @Override
    public void warn(Marker marker, Object o, Throwable throwable) {
        warn(String.valueOf(o), throwable);
    }

    @Override
    public void warn(Marker marker, String s) {
        warn(s);
    }

    @Override
    public void warn(Marker marker, String s, Object... objects) {
        warn(s, objects);
    }

    @Override
    public void warn(Marker marker, String s, Throwable throwable) {
        warn(s, throwable);
    }

    @Override
    public void warn(Message message) {
        warn(message.getFormattedMessage());
    }

    @Override
    public void warn(Message message, Throwable throwable) {
        warn(message.getFormattedMessage(), throwable);
    }

    @Override
    public void warn(Object o) {
        warn(String.valueOf(o));
    }

    @Override
    public void warn(Object o, Throwable throwable) {
        warn(String.valueOf(o), throwable);
    }

    @Override
    public void warn(String s) {
        warn(s, new Object[]{});
    }

    @Override
    public void warn(String s, Throwable throwable) {
        warn(s, new Object[]{throwable});
    }

    /**
     * Convert Log4j Level to internal LogLevel
     */
    private LogLevel getLogLevel(Level level) {
        if (level == Level.TRACE) {
            return LogLevel.DEBUG; // Map TRACE to DEBUG since we don't have TRACE
        } else if (level == Level.DEBUG) {
            return LogLevel.DEBUG;
        } else if (level == Level.INFO) {
            return LogLevel.INFO;
        } else if (level == Level.WARN) {
            return LogLevel.WARN;
        } else if (level == Level.ERROR) {
            return LogLevel.ERROR;
        } else if (level == Level.FATAL) {
            return LogLevel.CRITICAL;
        } else {
            return LogLevel.INFO;
        }
    }
}

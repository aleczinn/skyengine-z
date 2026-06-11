package de.skyengine.utils.logging;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.utils.ANSI;
import de.skyengine.utils.TimeUtils;

import java.time.LocalDateTime;

public class Logger {

    private final String name;

    protected Logger(String name) {
        this.name = name;
    }

    public void info(String message) {
        this.log(LogLevel.INFO, ANSI.WHITE, message, null, false);
    }

    public void info(String message, Throwable throwable) {
        this.log(LogLevel.INFO, ANSI.WHITE, message, throwable, false);
    }

    public void debug(String message) {
        /* Check FIRST - costs one branch instead of string building + stack trace */
        if (!isDebugEnabled()) return;
        this.log(LogLevel.DEBUG, ANSI.CYAN, message, null, false);
    }

    public void debug(String message, Throwable throwable) {
        if (!isDebugEnabled()) return;
        this.log(LogLevel.DEBUG, ANSI.CYAN, message, throwable, false);
    }

    public void warning(String message) {
        this.log(LogLevel.WARNING, ANSI.YELLOW, message, null, false);
    }

    public void warning(String message, Throwable throwable) {
        this.log(LogLevel.WARNING, ANSI.YELLOW, message, throwable, false);
    }

    public void warning(Throwable throwable) {
        this.log(LogLevel.WARNING, ANSI.YELLOW, null, throwable, false);
    }

    public void error(String message) {
        this.log(LogLevel.ERROR, ANSI.PURPLE, message, null, true);
    }

    public void error(String message, Throwable throwable) {
        this.log(LogLevel.ERROR, ANSI.PURPLE, message, throwable, true);
    }

    public void error(Throwable throwable) {
        this.log(LogLevel.ERROR, ANSI.PURPLE, null, throwable, true);
    }

    public void fatal(String message) {
        this.log(LogLevel.FATAL, ANSI.RED, message, null, true);
    }

    public void fatal(String message, Throwable throwable) {
        this.log(LogLevel.FATAL, ANSI.RED, message, throwable, true);
    }

    public void fatal(Throwable throwable) {
        this.log(LogLevel.FATAL, ANSI.RED, null, throwable, true);
    }

    private static boolean isDebugEnabled() {
        SkyEngine engine = SkyEngine.get();
        return engine != null && engine.getConfig().getDebugMode() == EngineConfig.DebugMode.FULL;
    }

    private void log(LogLevel level, String color, String message, Throwable throwable, boolean resolveMethod) {
        LocalDateTime now = LocalDateTime.now();
        String time = TimeUtils.timeFormatter.format(now);
        String date = TimeUtils.dateFormatter.format(now);
        String thread = Thread.currentThread().getName();

        /* Stack walking only for ERROR/FATAL - these are rare and worth the context */
        String method = resolveMethod ? callerMethodName() : "";

        if (message == null || message.isEmpty()) {
            message = "NULL";
        }

        StringBuilder sb = new StringBuilder(128);
        sb.append('[').append(time).append("] [").append(thread).append('/')
                .append(color).append(level).append(ANSI.RESET).append("] ")
                .append(this.name);
        if (!method.isEmpty()) {
            sb.append('#').append(method);
        }
        sb.append(" : ").append(message);

        System.out.println(sb);

        if (throwable != null) {
            throwable.printStackTrace();
        }

        LogManager.getLogmanager().add(thread, this.name, method, message, time, date, level, throwable);
    }

    /** StackWalker is lazy - it only materializes the frames we actually consume. */
    private static String callerMethodName() {
        return StackWalker.getInstance()
                .walk(frames -> frames.skip(3).findFirst())
                .map(StackWalker.StackFrame::getMethodName)
                .orElse("?");
    }

    public String getName() {
        return name;
    }
}

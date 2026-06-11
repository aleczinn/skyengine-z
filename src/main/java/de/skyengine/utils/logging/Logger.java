package de.skyengine.utils.logging;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.utils.ANSI;
import de.skyengine.utils.TimeUtils;

import java.time.LocalDateTime;

public class Logger {

    private final String name;
    public static final boolean useANSI = true;

    protected Logger(String name) {
        this.name = name;
    }

    public void info(String message) {
        this.message(message, LogLevel.INFO, null);
    }

    public void info(String message, Throwable throwable) {
        this.message(message, LogLevel.INFO, throwable);
    }

    public void debug(String message) {
        this.message(message, LogLevel.DEBUG, null);
    }

    public void debug(String message, Throwable throwable) {
        this.message(message, LogLevel.DEBUG, throwable);
    }

    public void warning(String message) {
        this.message(message, LogLevel.WARNING, null);
    }

    public void warning(String message, Throwable throwable) {
        this.message(message, LogLevel.WARNING, throwable);
    }

    public void warning(Throwable throwable) {
        this.message(null, LogLevel.WARNING, throwable);
    }

    public void error(String message) {
        this.message(message, LogLevel.ERROR, null);
    }

    public void error(String message, Throwable throwable) {
        this.message(message, LogLevel.ERROR, throwable);
    }

    public void error(Throwable throwable) {
        this.message(null, LogLevel.ERROR, throwable);
    }

    public void fatal(String message) {
        this.message(message, LogLevel.FATAL, null);
    }

    public void fatal(String message, Throwable throwable) {
        this.message(message, LogLevel.FATAL, throwable);
    }

    public void fatal(Throwable throwable) {
        this.message(null, LogLevel.FATAL, throwable);
    }

    private void message(String message, LogLevel level, Throwable throwable) {
        LocalDateTime ldt = LocalDateTime.now();

        String time = TimeUtils.timeFormatter.format(ldt);
        String date = TimeUtils.dateFormatter.format(ldt);

        String threadName = Thread.currentThread().getName();
        String method = new Throwable().getStackTrace()[2].getMethodName();

        if (message == null || message.isEmpty()) {
            message = "NULL";
        }

        switch (level) {
            case INFO:
                System.out.println("[" + time + "] " + "[" + threadName + "/" + ANSI.WHITE + level.toString() + ANSI.RESET + "] " + this.name + " " + method + " : " + message);
                break;
            case DEBUG:
                if (SkyEngine.get().getConfig().getDebugMode().equals(EngineConfig.DebugMode.FULL)) {
                    System.out.println("[" + time + "] " + "[" + threadName + "/" + ANSI.CYAN + level.toString() + ANSI.RESET + "] " + this.name + " " + method + " : " + message);
                }
                break;
            case WARNING:
                System.out.println("[" + time + "] " + "[" + threadName + "/" + ANSI.YELLOW + level.toString() + ANSI.RESET + "] " + this.name + " " + method + " : " + message);
                break;
            case ERROR:
                System.out.println("[" + time + "] " + "[" + threadName + "/" + ANSI.PURPLE + level.toString() + ANSI.RESET + "] " + this.name + " " + method + " : " + message);
                break;
            case FATAL:
                System.out.println("[" + time + "] " + "[" + threadName + "/" + ANSI.RED + level.toString() + ANSI.RESET + "] " + this.name + " " + method + " : " + message);
                break;
            default:
                break;
        }

        if (throwable != null) {
            throwable.printStackTrace();
        }

        LogManager.getLogmanager().add(threadName, this.name, method, message, time, date, level, throwable);
    }

    public String getName() {
        return name;
    }
}

package de.skyengine.utils;

import java.time.format.DateTimeFormatter;

public class TimeUtils {

    public static DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * @return The current value of the system timer, in nanoseconds.
     */
    public static long nanoTime() {
        return System.nanoTime();
    }

    /**
     * @return the difference, measured in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     */
    public static long millis() {
        return System.currentTimeMillis();
    }

    /**
     * Convert nanoseconds time to milliseconds
     *
     * @param nanos must be nanoseconds
     * @return time value in milliseconds
     */
    public static double nanosToMillis(long nanos) {
        return nanos / 1e3 / 1e3;
    }

    /**
     * Convert milliseconds time to nanoseconds
     *
     * @param millis must be milliseconds
     * @return time value in nanoseconds
     */
    public static double millisToNanos(long millis) {
        return millis * 1e3 * 1e3;
    }

    /**
     * Get the time in nanos passed since a previous time
     *
     * @param prevTime - must be nanoseconds
     * @return - time passed since prevTime in nanoseconds
     */
    public static long timeSinceNanos(long prevTime) {
        return nanoTime() - prevTime;
    }

    /**
     * Get the time in millis passed since a previous time
     *
     * @param prevTime - must be milliseconds
     * @return - time passed since prevTime in milliseconds
     */
    public static long timeSinceMillis(long prevTime) {
        return millis() - prevTime;
    }

    public static long milliToSeconds(long millis) {
        return millis / 1000L;
    }

    public static double milliToMinutes(long millis) {
        return millis / 1000.0 / 60.0;
    }

    /**
     * @return array | array[0] = minutes array[1] = seconds
     */
    public static String milliToCorrectMinutes(long millis) {
        long minutes = millis / 1000 / 60;
        long seconds = (millis / 1000) % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }
}

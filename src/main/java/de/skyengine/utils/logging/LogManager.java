package de.skyengine.utils.logging;

import de.skyengine.core.SkyEngine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class LogManager {

	private static LogManager instance;

	private static final String DIRECTORY = "logs/";
	private static final int MAX_LOGS = 10_000;

	/** Logger lookup by name - O(1) instead of scanning a list on every getLogger() call. */
	private final Map<String, Logger> logger = new HashMap<>();

	/** Bounded ring of the most recent logs. Oldest entries get dropped beyond MAX_LOGS. */
	private final ArrayDeque<Log> logs = new ArrayDeque<>(MAX_LOGS);

	protected void add(String thread, String packageName, String methodName, String message,
	                   String time, String date, LogLevel level, Throwable throwable) {
		Log log = new Log(thread, packageName, methodName, message, time, date, level, throwable);
		synchronized (this.logs) {
			if (this.logs.size() >= MAX_LOGS) {
				this.logs.pollFirst(); // drop oldest entry
			}
			this.logs.addLast(log);
		}
	}

	public void save() {
		File logDir = new File(DIRECTORY);
		if (!logDir.exists()) {
			logDir.mkdir();
		}

		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		Date date = new Date();

		String logName = dateFormat.format(date) + ".log";

		File latestFile = new File(DIRECTORY + "latest.log");
		if (!latestFile.exists()) {
			SkyEngine.get().getFiles().createFile(latestFile);
		}

		String latestDate = dateFormat.format(SkyEngine.get().getFiles().getLastModifiedTime(latestFile).toMillis());

		boolean foundLatestZip = false;
		for (File f : Objects.requireNonNull(logDir.listFiles())) {
			String fileDate = dateFormat.format(SkyEngine.get().getFiles().getLastModifiedTime(f).toMillis());

			if (!f.getName().equalsIgnoreCase(latestFile.getName()) && fileDate.equalsIgnoreCase(latestDate)) {
				foundLatestZip = true;
				break;
			}
		}

		StringBuilder builder = new StringBuilder();

		if (foundLatestZip) {
			builder.append("- - - - - - - - - - - - - - - [New Engine Start] - - - - - - - - - - - - - - -");
			builder.append('\n');
		}

		synchronized (this.logs) {
			for (Log log : this.logs) {
				builder.append('[').append(log.getTime()).append("] [")
						.append(log.getThread()).append('/').append(log.getLevel()).append("] ")
						.append(log.getPackageName()).append(' ').append(log.getMethodName())
						.append(" : ").append(log.getMessage());
				builder.append('\n');

				if (log.getThrowable() != null) {
					builder.append(SkyEngine.get().getFiles().getStackTrace(log.getThrowable()));
					builder.append('\n');
				}
			}
		}

		if (foundLatestZip) {
			File logZip = new File(DIRECTORY + logName + ".zip");
			logZip.delete();
		} else {
			SkyEngine.get().getFiles().clearFileContent(latestFile);
		}

		try (PrintWriter writer = new PrintWriter(new FileWriter(latestFile, true))) {
			writer.print(builder);
		} catch (IOException e) {
			e.printStackTrace();
		}

		SkyEngine.get().getFiles().zipFile(latestFile, DIRECTORY + logName);
	}

	/**
	 * @return a snapshot copy of the recent logs. Safe to iterate without holding the lock.
	 */
	public Collection<Log> getLogs() {
		synchronized (this.logs) {
			return new ArrayDeque<>(this.logs);
		}
	}

	public static Logger getLogger(String name) {
		LogManager manager = LogManager.getLogmanager();
		synchronized (manager.logger) {
			return manager.logger.computeIfAbsent(name, Logger::new);
		}
	}

	public static LogManager getLogmanager() {
		if (instance == null) {
			instance = new LogManager();
		}
		return instance;
	}
}
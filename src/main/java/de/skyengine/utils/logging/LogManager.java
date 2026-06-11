package de.skyengine.utils.logging;

import de.skyengine.core.SkyEngine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class LogManager {

	private static LogManager instance;
	private final String DIRECTORY = "logs/";
	
	private final List<Logger> logger = Collections.synchronizedList(new ArrayList<Logger>());
	private final List<Log> logs = Collections.synchronizedList(new ArrayList<Log>());
	
	protected void add(Log log) {
		if(log == null) throw new RuntimeException("The added log entry is null!");
		logs.add(log);
	}
	
	protected void add(String thread, String packageName, String methodName, String message, String time, String date, LogLevel level) {
		Log log = new Log(thread, packageName, methodName, message, time, date, level);
		logs.add(log);
	}
	
	protected void add(String thread, String packageName, String methodName, String message, String time, String date, LogLevel level, Throwable throwable) {
		Log log = new Log(thread, packageName, methodName, message, time, date, level, throwable);
		logs.add(log);
	}
	
	@SuppressWarnings("unused")
	@Deprecated
	private void saveOld() {
		File logFolder = new File(DIRECTORY);
		if(!logFolder.exists()) {
			logFolder.mkdirs();
		}
		
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		Date date = new Date();
		
		File logFile = new File(DIRECTORY + "/" + dateFormat.format(date) + ".log");
		
		StringBuilder builder = new StringBuilder();
		
		if(!logFile.exists()) {
			if(!SkyEngine.get().getFiles().createFile(logFile)) {
				System.err.println("Die Log Datei konnte nicht erstellt werden!");
			}
		} else {
			builder.append("- - - - - - - - - - - - - - - [New Engine Start] - - - - - - - - - - - - - - -");
			builder.append("\n");
		}
		
		synchronized(this.logs) {
			for(int i = 0; i < this.logs.size(); i++) {
				Log log = this.logs.get(i);
				
				if(log != null) {
					builder.append("[").append(log.getTime()).append("] [").append(log.getThread()).append("/").append(log.getLevel().toString()).append("] ").append(log.getPackageName()).append(" ").append(log.getMethodName()).append(" : ").append(log.getMessage());
					builder.append("\n");
					
					if(log.getThrowable() != null) {
						builder.append(SkyEngine.get().getFiles().getStackTrace(log.getThrowable()));
						builder.append("\n");
					}
				} else {
					System.err.println("Die Log konnte nicht in die Datei hinzugef�gt werden! " + i);
				}
			}
		}
		
		try {
			FileWriter fileWriter = new FileWriter(logFile, true);
			PrintWriter writer = new PrintWriter(fileWriter);
			writer.print(builder.toString());
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		SkyEngine.get().getFiles().zipFile(logFile);
	}
	
	public void save() {
		File logDir = new File(this.DIRECTORY);
		if(!logDir.exists()) {
			logDir.mkdir();
		}
		
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		Date date = new Date();
		
		String logName = dateFormat.format(date) + ".log";
		
		File latestFile = new File(this.DIRECTORY + "latest.log");
		if(!latestFile.exists()) {
			SkyEngine.get().getFiles().createFile(latestFile);
		}
		
		String latestDate = dateFormat.format(SkyEngine.get().getFiles().getLastModifiedTime(latestFile).toMillis());
		
		boolean foundLatestZip = false;
		for(File f : Objects.requireNonNull(logDir.listFiles())) {
			String fileDate = dateFormat.format(SkyEngine.get().getFiles().getLastModifiedTime(f).toMillis());
			
			if(!f.getName().equalsIgnoreCase(latestFile.getName()) && fileDate.equalsIgnoreCase(latestDate)) {
				foundLatestZip = true;
				break;
			}
		}
		
		StringBuilder builder = new StringBuilder();
		
		if(foundLatestZip) {
			builder.append("- - - - - - - - - - - - - - - [New Engine Start] - - - - - - - - - - - - - - -");
			builder.append("\n");
		}
		
		synchronized (this.logs) {
			for(int i = 0; i < this.logs.size(); i++) {
				Log log = this.logs.get(i);

				if(log != null) {
					builder.append("[" + log.getTime() + "] [" + log.getThread() + "/" + log.getLevel().toString() + "] " + log.getPackageName() + " " + log.getMethodName() + " : " + log.getMessage());
					builder.append("\n");

					if(log.getThrowable() != null) {
						builder.append(log.getThrowable().toString());
						builder.append("\n");
					}
				} else {
					System.err.println("Die Log konnte nicht in die Datei hinzugef�gt werden! " + i);
				}
			}
		}
		
		if(foundLatestZip) {
			File logZip = new File(this.DIRECTORY + logName + ".zip");
			logZip.delete();
		} else {
			SkyEngine.get().getFiles().clearFileContent(latestFile);
		}
		
		try {
			FileWriter fileWriter = new FileWriter(latestFile, true);
			PrintWriter writer = new PrintWriter(fileWriter);
			writer.print(builder.toString());
			writer.close();

			SkyEngine.get().getFiles().zipFile(latestFile, this.DIRECTORY + logName);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public List<Logger> getLoggers() {
		return logger;
	}
	
	public List<Log> getLogs() {
		return logs;
	}
	
	public static Logger getLogger(String name) {
		LogManager manager = LogManager.getLogmanager();
		
		for(Logger loggers : manager.getLoggers()) {
			if(loggers.getName().equalsIgnoreCase(name)) {
				return loggers;
			}
		}
		
		Logger l = new Logger(name);
		manager.getLoggers().add(l);
		return manager.getLoggers().get(manager.getLoggers().size() - 1);
	}
	
	public static LogManager getLogmanager() {
		if(instance == null) {
			instance = new LogManager();
		}
		return instance;
	}
}

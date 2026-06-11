package de.skyengine.utils.logging;

public class Log {

	private String thread = "NULL";
	private String packageName = "NULL";
	private String methodName = "NULL";
	
	private String message = "NULL";
	private String time = "NULL";
	private String date = "NULL";
	private LogLevel level = LogLevel.NONE;
	
	private final Throwable throwable;
	
	public Log(String thread, String packageName, String methodName, String message, String time, String date, LogLevel level) {
		this(thread, packageName, methodName, message, time, date, level, null);
	}
	
	public Log(String thread, String packageName, String methodName, String message, String time, String date, LogLevel level, Throwable throwable) {
		this.thread = thread;
		this.packageName = packageName;
		this.methodName = methodName;
		
		this.message = message;
		this.time = time;
		this.date = date;
		this.level = level;
		this.throwable = throwable;
	}
	
	public String getThread() {
		if(this.thread == null) return "NO THREAD";
		return thread;
	}
	
	public String getPackageName() {
		if(this.packageName == null) return "UNKNOWN PACKAGE";
		return packageName;
	}
	
	public String getMethodName() {
		if(this.methodName == null) return "UNKNOWN METHOD";
		return methodName;
	}
	
	public String getMessage() {
		if(this.message == null) return "-";
		return message;
	}
	
	public String getTime() {
		if(this.time == null) return "UNKNOWN TIME";
		return time;
	}
	
	public String getDate() {
		if(this.date == null) return "UNKNOWN DATE";
		return date;
	}
	
	public LogLevel getLevel() {
		if(this.level == null) return LogLevel.NONE;
		return level;
	}
	
	public Throwable getThrowable() {
		return throwable;
	}
}

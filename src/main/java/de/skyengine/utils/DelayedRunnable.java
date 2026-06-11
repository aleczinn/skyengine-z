package de.skyengine.utils;

import java.util.concurrent.Callable;

/**
 * An action that is optionally delayed a given number of frames.
 */
public class DelayedRunnable {

	private final String name;
	private final Callable<Void> runnable;
	private int delay;
	
	public DelayedRunnable(Callable<Void> runnable, String name, int delay) {
		this.runnable = runnable;
		this.name = name;
		this.delay = delay;
	}
	
	public String getName() {
		return name;
	}
	
	public Callable<Void> getRunnable() {
		return runnable;
	}
	
	public int getDelay() {
		return delay;
	}
	
	public void reduceDelay() {
		this.delay--;
	}
}

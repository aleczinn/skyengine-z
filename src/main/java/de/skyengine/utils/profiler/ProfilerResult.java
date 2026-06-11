package de.skyengine.utils.profiler;

import de.skyengine.utils.MathUtils;

public class ProfilerResult implements Comparable<ProfilerResult>{

	private final String name;
	
	private long time;
	private long totalTime;
	private double percentage;

	public ProfilerResult(String name, long time) {
		this.name = name;
		this.time = time;
		this.totalTime = time;
		
		this.percentage = 0;
	}
	
	public void update(long time) {
		this.time = time;
		this.totalTime += time;
	}

	public String getName() {
		return name;
	}

	public long getTime() {
		return time;
	}

	public long getTotalTime() {
		return totalTime;
	}
	
	public double getPercentage() {
		return percentage;
	}
	
	public void setPercentage(double percentage) {
		this.percentage = percentage;
	}
	
	@Override
	public int compareTo(ProfilerResult o) {
		if(o.getTime() < this.time) {
			return -1;
		}
		return 1;
	}
	
	@Override
	public String toString() {
		return this.name + " : " + "Time: " + this.time + "ns " + MathUtils.nanoToMilli(this.time) + "ms " + ", TotalTime=" + this.totalTime + "ns " + MathUtils.nanoToMilli(this.totalTime) + "ms [" + this.percentage + "%]";
	}
}

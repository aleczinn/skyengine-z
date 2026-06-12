package de.skyengine.utils.profiler;

import de.skyengine.utils.TimeUtils;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.*;

public class Profiler {

	private static final Logger logger = LogManager.getLogger(Profiler.class.getName());
	public static final Profiler INSTANCE = new Profiler();
	
	/** Flag profiling enabled */
	public boolean profilingEnabled = true;
	
	private String profilingSection = "";
	private long allocatedMemory = 0L;
	private long allocatedGPUMemory = 0L;

	private long maxMemory = 0L;
	private long totalMemory = 0L;
	private long freeMemory = 0L;
	private long usedMemory = 0;

	private final Map<String, ProfilerResult> map;
    private final List<String> sectionList;
    private final List<Long> timestampList;

    private long renderTime = 0L;
    private long renderTimeCurrent = 0L;

	public Profiler() {
		this.map = new HashMap<>();
		this.sectionList = new ArrayList<>();
		this.timestampList = new ArrayList<>();
	}
	
	public void startSection(String name) {
		if(this.profilingEnabled) {
			if(!this.profilingSection.isEmpty()) {
				this.profilingSection += ".";
			}
			
			this.profilingSection += name;
			this.sectionList.add(this.profilingSection);
			this.timestampList.add(System.nanoTime());
		}
	}
	
	public void endSection() {
		if(this.profilingEnabled) {
			long now = System.nanoTime();
			long last = this.timestampList.remove(this.timestampList.size() - 1);
			long time = now - last;
			
			this.sectionList.remove(this.sectionList.size() - 1);
			
			if(this.map.containsKey(this.profilingSection)) {
				ProfilerResult result = this.map.get(this.profilingSection);
				result.update(time);
				
				this.map.put(this.profilingSection, result);
			} else {
				this.map.put(this.profilingSection, new ProfilerResult(this.profilingSection, time));
			}
			
			if(time > 100000000L) {
				logger.fatal("Something's taking too long! '" + this.profilingSection + "' took " + TimeUtils.nanosToMillis(time) + "ms");
			}
			
			this.profilingSection = this.sectionList.isEmpty() ? "" : this.sectionList.get(this.sectionList.size() - 1);
		}
	}
	
	public void memoryIncrease(long memory) {
		if(this.profilingEnabled) {
			this.allocatedMemory += memory;
		}
	}
	
	public void memoryDecrease(long memory) {
		if(this.profilingEnabled) {
			this.allocatedMemory -= memory;
		}
	}
	
	public long getAllocatedMemory() {
		return this.allocatedMemory;
	}

	public double getAllocatedMemoryInMb() {
		return this.allocatedMemory / 1024.0 / 1024.0;
	}	
	
	public double getAllocatedMemoryInGb() {
		return this.allocatedMemory / 1024.0 / 1024.0 / 1024.0;
	}
	
	public void gpuMemoryIncrease(long memory) {
		if(this.profilingEnabled) {
			this.allocatedGPUMemory += memory;
		}
	}
	
	public void gpuMemoryDecrease(long memory) {
		if(this.profilingEnabled) {
			this.allocatedGPUMemory -= memory;
		}
	}
	
	public long getAllocatedGPUMemory() {
		return this.allocatedGPUMemory;
	}

	public double getAllocatedGPUMemoryInMb() {
		return this.allocatedGPUMemory / 1024.0 / 1024.0;
	}	
	
	public double getAllocatedGPUMemoryInGb() {
		return this.allocatedGPUMemory / 1024.0 / 1024.0 / 1024.0;
	}

	public void updateEverySecond() {
		Runtime runtime = Runtime.getRuntime();

		this.maxMemory = runtime.maxMemory();
		this.totalMemory = runtime.totalMemory();
		this.freeMemory = runtime.freeMemory();
		this.usedMemory = this.totalMemory - this.freeMemory;

		this.renderTime = this.renderTimeCurrent;
	}

	public long getMaxMemoryInBytes() {
		return maxMemory;
	}

	public long getMaxMemoryInMegaBytes() {
		return maxMemory / 1024L / 1024L;
	}

	public long getTotalMemoryInBytes() {
		return totalMemory;
	}

	public long getTotalMemoryInMegaBytes() {
		return totalMemory / 1024L / 1024L;
	}

	public long getFreeMemoryInBytes() {
		return freeMemory;
	}

	public long getFreeMemoryInMegaBytes() {
		return freeMemory / 1024L / 1024L;
	}

	public long getUsedMemoryInBytes() {
		return usedMemory;
	}

	public long getUsedMemoryInMegaBytes() {
		return usedMemory / 1024L / 1024L;
	}

	public int getMemoryUsageInPercent() {
		if(this.usedMemory == 0) return 0;
		return (int) (this.usedMemory * 100 / this.maxMemory);
	}

	public void clearProfiling() {
		this.map.clear();
		this.profilingSection = "";
		this.sectionList.clear();
		this.timestampList.clear();
	}
	
	public List<ProfilerResult> getResults() {
		if(this.profilingEnabled) {
			List<ProfilerResult> results = new ArrayList<>();
			
			long totalTime = 0L;
			for(ProfilerResult result : this.map.values()) {
				totalTime += result.getTime();
			}
			
			for(ProfilerResult result : this.map.values()) {
				double p = 100.0 / (double) totalTime * result.getTime();
				
				result.setPercentage(p);
				results.add(result);
			}
			
			Collections.sort(results);
			return results;
		}
		return new ArrayList<>();
	}
	
	public void print() {
		if(this.profilingEnabled) {
			List<ProfilerResult> list = this.getResults();
			
			System.out.println("============= [ Profiling ] =============");
			for(ProfilerResult result : list) {
				System.out.println(result.toString());
			}
		}
	}

	/**
	 * @return the render time in nanoseconds, but it only updates every second.
	 */
	public long getRenderTime() {
		return this.renderTime;
	}

	/**
	 * @return the current render time in nanoseconds.
	 */
	public long getRenderTimeCurrent() {
		return renderTimeCurrent;
	}

	public void updateRenderTime(long time) {
		this.renderTimeCurrent = time;
	}
}

package de.skyengine.utils;

import de.skyengine.core.EngineConfig;
import de.skyengine.core.SkyEngine;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;
import de.skyengine.utils.math.MathUtils;
import org.lwjgl.system.MemoryUtil;

public class DynamicByteBuffer {

    private final Logger logger = LogManager.getLogger(DynamicByteBuffer.class.getName());

    private long address;
    private int position;
    private int capacity;

    /**
     * Allocate a ByteBuffer with the given initial capacity.
     */
    public DynamicByteBuffer(int initialCapacity) {
        if (initialCapacity < 1) throw new RuntimeException("Intitial capacity needs to be bigger than 0!");

        this.address = MemoryUtil.nmemAlloc(initialCapacity);
        this.capacity = initialCapacity;
        this.position = 0;

        if (SkyEngine.get().getConfig().getDebugMode().equals(EngineConfig.DebugMode.FULL)) {
            this.logger.debug("Creating new DynamicByteBuffer with capacity [" + MathUtils.round(this.capacity / 1024F, 2) + " KB]");
        }
    }

    private void grow() {
        int newCapacity = (int) (this.capacity * 1.75F);
        this.logger.debug("Growing DynamicByteBuffer from [" + MathUtils.round(this.capacity / 1024F, 2) + " KB] to [" + MathUtils.round(newCapacity / 1024F, 2) + " KB]");

        long newAddress = MemoryUtil.nmemRealloc(this.address, newCapacity);
        this.capacity = newCapacity;
        this.address = newAddress;
    }

    // Short
    public DynamicByteBuffer putShort(int v) {
        if (this.capacity - this.position < Short.BYTES) {
            this.grow();
        }
        return putShortNoGrow(v);
    }

    private DynamicByteBuffer putShortNoGrow(int v) {
        MemoryUtil.memPutShort(this.address + this.position, (short) v);
        this.position += Short.BYTES;
        return this;
    }

    // Int
    public DynamicByteBuffer putInt(int v) {
        if (this.capacity - this.position < Integer.BYTES) {
            this.grow();
        }
        return this.putIntNoGrow(v);
    }

    private DynamicByteBuffer putIntNoGrow(int v) {
        MemoryUtil.memPutInt(this.address + this.position, v);
        this.position += Integer.BYTES;
        return this;
    }

    // Float
    public DynamicByteBuffer putFloat(float v) {
        if (this.capacity - this.position < Float.BYTES) {
            this.grow();
        }
        return this.putFloatNoGrow(v);
    }

    private DynamicByteBuffer putFloatNoGrow(float v) {
        MemoryUtil.memPutFloat(this.address + this.position, v);
        this.position += Float.BYTES;
        return this;
    }

    // Double
    public DynamicByteBuffer putDouble(double v) {
        if (this.capacity - this.position < Double.BYTES) {
            this.grow();
        }
        return this.putDoubleNoGrow(v);
    }

    private DynamicByteBuffer putDoubleNoGrow(double v) {
        MemoryUtil.memPutDouble(this.address + this.position, v);
        this.position += Double.BYTES;
        return this;
    }

    // Long
    public DynamicByteBuffer putLong(long v) {
        if (this.capacity - this.position < Long.BYTES) {
            this.grow();
        }
        return this.putLongNoGrow(v);
    }

    private DynamicByteBuffer putLongNoGrow(long v) {
        MemoryUtil.memPutLong(this.address + this.position, v);
        this.position += Long.BYTES;
        return this;
    }

    public void free() {
        if (SkyEngine.get().getConfig().getDebugMode().equals(EngineConfig.DebugMode.FULL)) {
            this.logger.debug("Freeing DynamicByteBuffer (used " + MathUtils.round((float) this.position / this.capacity, 2) + "% of capacity)");
        }

        MemoryUtil.nmemFree(this.address);
    }

    public long getAddress() {
        return address;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}

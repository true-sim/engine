package com.truesim.engine.platform;

import static org.lwjgl.sdl.SDLSensor.SDL_CloseSensor;
import static org.lwjgl.sdl.SDLSensor.SDL_GetSensorID;
import static org.lwjgl.sdl.SDLSensor.SDL_GetSensorName;
import static org.lwjgl.sdl.SDLSensor.SDL_GetSensorType;
import static org.lwjgl.sdl.SDLSensor.nSDL_GetSensorData;
import static org.lwjgl.system.MemoryUtil.memGetFloat;

import com.truesim.annotations.AllowAllocation;
import org.lwjgl.system.MemoryStack;

/**
 * A device's own motion sensor, e.g. a phone's or tablet's accelerometer ({@link Platform#openSensor}); gamepad
 * sensors are on the {@link Gamepad}. While open, readings arrive through {@link PlatformListener#sensor}. Use it on
 * the platform's thread.
 */
public final class Sensor implements AutoCloseable {

    private final Platform platform;
    private final long handle;
    private boolean closed;

    Sensor(Platform platform, long handle) {
        this.platform = platform;
        this.handle = handle;
    }

    /** The id {@link PlatformListener#sensor} events carry. */
    public int id() {
        check();
        return SDL_GetSensorID(handle);
    }

    @AllowAllocation("a name is a string")
    public String name() {
        check();
        return Controller.orEmpty(SDL_GetSensorName(handle));
    }

    public SensorType type() {
        check();
        return Sdl.sensor(SDL_GetSensorType(handle));
    }

    /** Writes the latest x, y, z into {@code out}; false when there is no reading. */
    public boolean read(float[] out) {
        check();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long data = stack.nmalloc(Float.BYTES, 3 * Float.BYTES);
            if (!nSDL_GetSensorData(handle, data, 3)) {
                return false;
            }
            for (int i = 0; i < 3; i++) {
                out[i] = memGetFloat(data + (long) i * Float.BYTES);
            }
            return true;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private void check() {
        platform.checkThread();
        if (closed) {
            throw new IllegalStateException("The sensor is closed");
        }
    }

    @Override
    @AllowAllocation("closing")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        SDL_CloseSensor(handle);
        platform.closed(this);
    }
}

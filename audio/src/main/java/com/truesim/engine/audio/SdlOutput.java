package com.truesim.engine.audio;

import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.lwjgl.sdl.SDLAudio.SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK;
import static org.lwjgl.sdl.SDLAudio.SDL_AUDIO_F32;
import static org.lwjgl.sdl.SDLAudio.SDL_DestroyAudioStream;
import static org.lwjgl.sdl.SDLAudio.SDL_GetAudioDeviceFormat;
import static org.lwjgl.sdl.SDLAudio.SDL_GetAudioDeviceName;
import static org.lwjgl.sdl.SDLAudio.SDL_GetAudioPlaybackDevices;
import static org.lwjgl.sdl.SDLAudio.SDL_GetCurrentAudioDriver;
import static org.lwjgl.sdl.SDLAudio.SDL_OpenAudioDeviceStream;
import static org.lwjgl.sdl.SDLAudio.SDL_PauseAudioStreamDevice;
import static org.lwjgl.sdl.SDLAudio.SDL_ResumeAudioStreamDevice;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLHints.SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES;
import static org.lwjgl.sdl.SDLHints.SDL_HINT_AUDIO_DEVICE_STREAM_ROLE;
import static org.lwjgl.sdl.SDLHints.SDL_HINT_AUDIO_DRIVER;
import static org.lwjgl.sdl.SDLHints.SDL_SetHint;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_AUDIO;
import static org.lwjgl.sdl.SDLInit.SDL_InitSubSystem;
import static org.lwjgl.sdl.SDLInit.SDL_WasInit;
import static org.lwjgl.sdl.SDLStdinc.SDL_free;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.IntBuffer;
import org.lwjgl.sdl.SDLAudio;
import org.lwjgl.sdl.SDL_AudioSpec;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The output through SDL3's audio stream in push mode (AU1, §2.3): the stream opened on the device in its own format
 * (float32, its channels and native rate), so SDL converts nothing and the queued bytes are what waits; no callback, so
 * no native thread ever calls into Java. A Java platform thread at the highest priority (and MMCSS "Pro Audio" on
 * Windows) keeps the queue between a low mark and a high mark (low plus one block): it renders a block whenever the
 * queue falls below the low mark, then sleeps until the queue is expected to reach it again ({@link Waiter}: a
 * high-resolution timer on Windows). Before resuming the device it renders 200 silent blocks so the JIT compiles the
 * mixer. Underruns are counted and two within a minute raise the low mark by a block for the session; so does an
 * oversleep whose 99.9th percentile exceeds half a block. {@code SDL_PutAudioStreamData} and
 * {@code SDL_GetAudioStreamQueued} go through kept downcall handles, so the loop allocates nothing.
 *
 * <pre>{@code
 * SdlOutput out = SdlOutput.open(null, 256, 2);              // the default device, which SDL follows
 * AudioRenderer renderer = new AudioRenderer(out.format(), 1024);
 * out.start(renderer);
 * }</pre>
 */
@SuppressWarnings("restricted") // kept downcall handles into SDL
public final class SdlOutput implements AudioOutput {

    private static final Logger LOG = LoggerFactory.getLogger(SdlOutput.class);
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle PUT = LINKER.downcallHandle(
        FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle QUEUED = LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MemorySegment PUT_FUNCTION = MemorySegment.ofAddress(SDLAudio.Functions.PutAudioStreamData);
    private static final MemorySegment QUEUED_FUNCTION =
        MemorySegment.ofAddress(SDLAudio.Functions.GetAudioStreamQueued);
    private static final int WARM_UP_BLOCKS = 200;
    private static final int OVERSLEEP_BUCKETS = 400; // 50 µs each: up to 20 ms

    private final int device;
    private final String deviceName;
    private final AudioFormat format;
    private final int deviceFrames;
    private final long stream;
    private volatile int lowMark;
    private volatile boolean running;
    private Thread thread;
    private AudioRenderer renderer;
    private volatile long underruns;
    private volatile long blocks;
    private final long[] underrunTimes = new long[2];
    private final int[] oversleep = new int[OVERSLEEP_BUCKETS + 1];
    private long sleeps;
    private volatile long oversleep999;
    private volatile boolean precise;
    private volatile boolean proAudio;

    private SdlOutput(int device, String name, AudioFormat format, int deviceFrames, long stream, int lowMark) {
        this.device = device;
        deviceName = name;
        this.format = format;
        this.deviceFrames = deviceFrames;
        this.stream = stream;
        this.lowMark = lowMark;
    }

    /**
     * Starts SDL's audio subsystem on this thread where no platform did ({@code Platform.startAudio} does it on the
     * platform's thread): tools and tests. {@code driver} {@code null} for SDL's choice.
     */
    @AllowAllocation("starting up")
    public static void startSdlAudio(String driver) {
        if (SDL_WasInit(SDL_INIT_AUDIO) != 0) {
            return;
        }
        if (driver != null) {
            SDL_SetHint(SDL_HINT_AUDIO_DRIVER, driver);
        }
        SDL_SetHint(SDL_HINT_AUDIO_DEVICE_STREAM_ROLE, "Game");
        if (!SDL_InitSubSystem(SDL_INIT_AUDIO)) {
            throw new IllegalStateException("SDL audio could not start: " + SDL_GetError());
        }
    }

    /**
     * Opens the playback device called {@code deviceName} ({@code null}, or a name no device has: the system's default
     * device, which SDL follows when the system changes it) in its own format, in blocks of {@code block} frames, the
     * queue kept at {@code lowMark} blocks or more. SDL's audio must have been started.
     */
    @AllowAllocation("opening a device")
    public static SdlOutput open(String deviceName, int block, int lowMark) {
        if (SDL_WasInit(SDL_INIT_AUDIO) == 0) {
            throw new IllegalStateException("SDL audio is not started: Platform.startAudio or startSdlAudio first");
        }
        return open(find(deviceName), block, lowMark, null);
    }

    /**
     * Opens the default device again for a renderer of {@code format} (its device went away): the stream takes the
     * renderer's format, and SDL converts if the default device's differs (§2.17).
     */
    @AllowAllocation("opening a device")
    public static SdlOutput reopen(AudioFormat format, int lowMark) {
        return open(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, format.block(), lowMark, format);
    }

    @AllowAllocation("opening a device")
    private static SdlOutput open(int device, int block, int lowMark, AudioFormat given) {
        // a request only (§2.3): SDL's device thread pulls this many frames at a time where the backend allows
        SDL_SetHint(SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES, Integer.toString(block));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_AudioSpec spec = SDL_AudioSpec.calloc(stack);
            IntBuffer frames = stack.mallocInt(1);
            if (!SDL_GetAudioDeviceFormat(device, spec, frames)) {
                throw new IllegalStateException("No audio device format: " + SDL_GetError());
            }
            int channels = switch (spec.channels()) {
                case 1, 2, 4, 6, 8 -> spec.channels();
                default -> 2;
            };
            AudioFormat format = given != null ? given : new AudioFormat(spec.freq(), channels, block);
            spec.format(SDL_AUDIO_F32).channels(format.channels()).freq(format.rate());
            long stream = SDL_OpenAudioDeviceStream(device, spec, null, 0);
            if (stream == 0) {
                throw new IllegalStateException("The audio device could not open: " + SDL_GetError());
            }
            String name = device == SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK ? "default" : SDL_GetAudioDeviceName(device);
            LOG.info("Audio output on {} ({}): {} Hz, {} channels, device buffer {} frames, blocks of {}",
                name, SDL_GetCurrentAudioDriver(), format.rate(), format.channels(), frames.get(0), block);
            return new SdlOutput(device, name, format, frames.get(0), stream, lowMark);
        }
    }

    /**
     * Opens playback device {@code name} for a renderer of {@code format} (the headset's device, chosen while playing):
     * the stream takes the renderer's format; SDL converts if the device's differs.
     */
    @AllowAllocation("opening a device")
    public static SdlOutput reopen(String name, AudioFormat format, int lowMark) {
        return open(find(name), format.block(), lowMark, format);
    }

    /** Whether a playback device is called {@code name}. */
    @AllowAllocation("listing devices")
    public static boolean has(String name) {
        IntBuffer devices = SDL_GetAudioPlaybackDevices();
        if (devices == null) {
            return false;
        }
        try {
            for (int i = 0; i < devices.remaining(); i++) {
                if (name.equals(SDL_GetAudioDeviceName(devices.get(i)))) {
                    return true;
                }
            }
            return false;
        } finally {
            SDL_free(devices);
        }
    }

    /** The playback device called {@code name}, or the default one. */
    @AllowAllocation("opening a device")
    private static int find(String name) {
        if (name == null || name.isEmpty()) {
            return SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK;
        }
        IntBuffer devices = SDL_GetAudioPlaybackDevices();
        if (devices == null) {
            return SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK;
        }
        try {
            for (int i = 0; i < devices.remaining(); i++) {
                if (name.equals(SDL_GetAudioDeviceName(devices.get(i)))) {
                    return devices.get(i);
                }
            }
        } finally {
            SDL_free(devices);
        }
        LOG.warn("No audio device is called {}: the default device plays", name);
        return SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK;
    }

    /** The format the device plays: make the renderer with it. */
    public AudioFormat format() {
        return format;
    }

    /** The device's id: the default playback device's for the default. */
    public int device() {
        return device;
    }

    /** Whether it plays on a device chosen by name (it must reopen if that device goes away). */
    public boolean chosen() {
        return device != SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK;
    }

    public String deviceName() {
        return deviceName;
    }

    /** The device's buffer in frames, as SDL reports it. */
    public int deviceFrames() {
        return deviceFrames;
    }

    /** The low mark, in blocks: the least the queue is kept at. */
    public int lowMark() {
        return lowMark;
    }

    /** Blocks pushed since started. */
    public long blocks() {
        return blocks;
    }

    @Override
    public long underruns() {
        return underruns;
    }

    /** The 99.9th percentile of how much later than asked the thread woke, in nanoseconds (50 µs steps). */
    public long oversleep999() {
        return oversleep999;
    }

    /** Whether the thread sleeps on a high-resolution timer. */
    public boolean precise() {
        return precise;
    }

    /** Whether the thread got MMCSS "Pro Audio". */
    public boolean proAudio() {
        return proAudio;
    }

    /**
     * The queue's low mark in frames: the low mark's blocks, and at least the device's buffer and a block, since SDL's
     * device thread takes a whole device buffer from the stream at once (the dummy driver 1,024 frames).
     */
    public int lowFrames() {
        return Math.max(lowMark * format.block(), deviceFrames + format.block());
    }

    /** Frames from the moment a block is pushed to the moment it is heard, at most: the high mark and the device. */
    public int latencyFrames() {
        return lowFrames() + format.block() + deviceFrames;
    }

    /**
     * The scheduling latency (§2.4): the limiter's look-ahead and four blocks, so commands a game frame writes (up to a
     * 60 Hz frame later than the clock was read) still reach the renderer before their frame. The queue is not part of
     * it: frames rendered are already that far ahead of the ear.
     */
    static int scheduling(AudioRenderer renderer) {
        return renderer.latency() + 4 * renderer.format().block();
    }

    @Override
    @AllowAllocation("starting up")
    public synchronized void start(AudioRenderer renderer) {
        if (running) {
            throw new IllegalStateException("The output runs already");
        }
        if (!renderer.format().equals(format)) {
            throw new IllegalArgumentException("The renderer's format " + renderer.format() + " is not the device's "
                + format);
        }
        this.renderer = renderer;
        renderer.clock().device(deviceFrames);
        renderer.clock().latency(scheduling(renderer));
        running = true;
        thread = Thread.ofPlatform().name("truesim-audio").priority(Thread.MAX_PRIORITY).daemon().unstarted(this::run);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        SDL_PauseAudioStreamDevice(stream);
    }

    @Override
    public void close() {
        stop();
        SDL_DestroyAudioStream(stream);
    }

    /** The audio thread: warm up, resume the device, keep the queue between the marks. */
    private void run() {
        proAudio = Waiter.proAudio();
        int channels = format.channels();
        int size = format.block();
        int bytes = size * channels * 4;
        float[] block = new float[size * channels];
        long rate = format.rate();
        try (Waiter waiter = new Waiter(); Arena arena = Arena.ofConfined()) {
            precise = waiter.precise();
            MemorySegment buffer = arena.allocate(bytes, 16);
            for (int i = 0; i < WARM_UP_BLOCKS; i++) {
                renderer.warmUp(block);
            }
            boolean resumed = false;
            long pushedAt = System.nanoTime();
            int pushedQueue = 0;
            while (running) {
                int queued = queued() / (channels * 4);
                int low = lowFrames();
                if (queued < low) {
                    // an empty queue alone is no underrun: the device takes a whole buffer at once and may hold it
                    // yet; it ran dry when more time passed since the last push than that push left it to play
                    long now = System.nanoTime();
                    if (queued == 0 && resumed && (now - pushedAt) * rate > (pushedQueue + deviceFrames) * 1e9) {
                        underrun();
                    }
                    renderer.render(block, 0, size);
                    MemorySegment.copy(block, 0, buffer, JAVA_FLOAT, 0, block.length);
                    put(buffer.address(), bytes);
                    blocks++;
                    pushedAt = System.nanoTime();
                    pushedQueue = queued + size;
                    renderer.clock().publish(renderer.frame(), pushedAt, pushedQueue);
                    continue;
                }
                if (!resumed) { // the queue holds its low mark: the device may start pulling
                    SDL_ResumeAudioStreamDevice(stream);
                    resumed = true;
                }
                // asleep until about a millisecond before the queue falls to the low mark, else a millisecond
                long ask = Math.max(1_000_000L, (queued - low) * 1_000_000_000L / rate - 1_000_000L);
                long before = System.nanoTime();
                waiter.sleep(ask);
                oversleep(System.nanoTime() - before - ask);
            }
        } catch (Throwable e) {
            LOG.error("The audio thread failed: audio stops", e);
            running = false;
        }
    }

    private int queued() {
        try {
            return (int) QUEUED.invokeExact(QUEUED_FUNCTION, stream);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private void put(long address, int bytes) {
        try {
            boolean ok = (boolean) PUT.invokeExact(PUT_FUNCTION, stream, address, bytes);
            if (!ok) {
                throw new IllegalStateException("SDL refused audio data");
            }
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Counts an underrun; two within a minute raise the low mark a block (to 6 at most). */
    private void underrun() {
        long now = System.nanoTime();
        underruns++;
        long earlier = underrunTimes[0];
        underrunTimes[0] = underrunTimes[1];
        underrunTimes[1] = now;
        if (underruns >= 2 && now - earlier < 60_000_000_000L && lowMark < 6) {
            raise();
        }
    }

    /** Records an oversleep; every 2,000 sleeps checks the 99.9th percentile against half a block. */
    private void oversleep(long nanos) {
        int bucket = (int) Math.min(OVERSLEEP_BUCKETS, Math.max(0, nanos / 50_000));
        oversleep[bucket]++;
        if (++sleeps % 2_000 == 0) {
            long target = sleeps - sleeps / 1_000;
            long seen = 0;
            int b = 0;
            while (b < OVERSLEEP_BUCKETS && (seen += oversleep[b]) < target) {
                b++;
            }
            oversleep999 = (b + 1) * 50_000L;
            if (oversleep999 * format.rate() > 500_000_000L * format.block() && lowMark < 6) {
                raise();
                java.util.Arrays.fill(oversleep, 0);
                sleeps = 0;
            }
        }
    }

    @AllowAllocation("logging a rare change")
    private void raise() {
        lowMark++;
        LOG.warn("Audio: the queue's low mark rises to {} blocks ({} underruns, oversleep p99.9 {} µs)", lowMark,
            underruns, oversleep999 / 1_000);
    }

    /** The bytes SDL holds for the stream now (for tests). */
    int queuedBytes() {
        return queued();
    }
}

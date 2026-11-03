package com.truesim.engine.audio;

import static org.lwjgl.sdl.SDLInit.SDL_INIT_AUDIO;
import static org.lwjgl.sdl.SDLInit.SDL_QuitSubSystem;

import com.truesim.engine.platform.Platform;
import com.truesim.engine.platform.Window;
import com.truesim.engine.platform.WindowOptions;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.Bus;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Measures the output on the real device (§2.24, step 6), behind {@code TRUESIM_AUDIO=true} like {@code TRUESIM_XR}:
 * the latency from a command to the ear, underruns over a run (with a thread making garbage at a high rate, and under
 * ZGC, when asked), the thread's oversleep (with the platform's window minimised, when asked), and what a block costs
 * offline with 64 voices. It prints; it asserts nothing about numbers (they are machine facts, reported).
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_AUDIO", matches = "true")
class AudioDeviceProbe {

    @Test
    void probe() throws InterruptedException {
        double seconds = Double.parseDouble(System.getProperty("truesim.audio.seconds", "20"));
        boolean garbage = Boolean.getBoolean("truesim.audio.garbage");
        boolean minimised = Boolean.getBoolean("truesim.audio.minimised");
        StringBuilder gc = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.append(bean.getName()).append("; ");
        }
        System.out.println("PROBE collector: " + gc);
        Platform platform = Platform.start();
        Window window = null;
        try {
            platform.startAudio(null);
            System.out.println("PROBE driver: " + Platform.runningAudioDriver());
            if (minimised) {
                window = platform.createWindow(WindowOptions.of("audio probe", 320, 200));
                window.minimize();
            }
            try (SdlOutput out = SdlOutput.open(null, 256, 2); AudioRenderer r = new AudioRenderer(out.format(), 1024)) {
                AudioFormat f = out.format();
                out.start(r);
                AudioClip tone = Clips.sine(f.rate(), 220, 0.05f, f.rate() / 220 * 220);
                r.commands().play(0, AudioClip.builder(f.rate(), 1).floats(sineLoop(f.rate())).build(),
                    r.clock().schedulePoint(), 0, 1, 1, Bus.SFX, Voices.LOOP, 0, 1);
                AtomicBoolean making = new AtomicBoolean(garbage);
                Thread maker = new Thread(() -> {
                    long sink = 0;
                    while (making.get()) {
                        byte[] junk = new byte[4096];
                        sink += junk.length;
                        if ((sink & 0xFFFFFFF) == 0) {
                            Thread.onSpinWait();
                        }
                    }
                });
                if (garbage) {
                    maker.start();
                }
                long end = System.nanoTime() + (long) (seconds * 1e9);
                while (System.nanoTime() < end) {
                    platform.pollEvents(new com.truesim.engine.platform.PlatformListener() { });
                    Thread.sleep(10);
                }
                making.set(false);
                maker.join();
                double ms = 1000.0 / f.rate();
                System.out.printf("PROBE format: %s; device buffer %d frames (%.2f ms); low mark %d blocks"
                        + " (%d frames, %.2f ms)%n", f, out.deviceFrames(), out.deviceFrames() * ms, out.lowMark(),
                    out.lowFrames(), out.lowFrames() * ms);
                System.out.printf("PROBE latency: schedule %d frames (%.2f ms) ahead of the rendered frame;"
                        + " rendered to heard at most %d frames (%.2f ms); command to ear at most %.2f ms%n",
                    r.clock().latency(), r.clock().latency() * ms, out.latencyFrames(), out.latencyFrames() * ms,
                    (r.clock().latency() + out.latencyFrames()) * ms);
                System.out.printf("PROBE run: %.0f s, %d blocks, %d underruns, garbage %s, minimised %s,"
                        + " oversleep p99.9 %d us, precise timer %s, MMCSS %s%n", seconds, out.blocks(),
                    out.underruns(), garbage, minimised, out.oversleep999() / 1_000, out.precise(), out.proAudio());
                tone.close();
            }
        } finally {
            if (window != null) {
                window.close();
            }
            SDL_QuitSubSystem(SDL_INIT_AUDIO);
            platform.close();
        }
    }

    private static float[] sineLoop(int rate) {
        float[] s = new float[rate];
        for (int i = 0; i < rate; i++) {
            s[i] = (float) (0.05 * Math.sin(2 * Math.PI * 220 * i / rate));
        }
        return s;
    }

    /** What a block costs with 1,000 placed loops, 64 of them real (the others keep time), choosing every 4 blocks. */
    @Test
    void renderCostWithAThousandLogicalVoices() {
        try (AudioRenderer r = new AudioRenderer(AudioFormat.stereo(48_000), 1_024)) {
            AudioClip tone = Clips.sine(44_100, 220, 0.02f, 44_100);
            com.truesim.scene.audio.Attenuation a = com.truesim.scene.audio.Attenuation.builder().distance(1, 2_000)
                .lowPass(20_000, 2_000).spread(1, 0).build();
            for (int v = 0; v < 1_000; v++) {
                r.commands().play(v, tone, 0, 0, 0.5f, 0.8f + (v % 50) * 0.01f, Bus.SFX,
                    Voices.LOOP | Voices.SPATIAL, 0, v);
                r.commands().spatial(v, (v % 7) - 3, 0, -(1 + v), 1, a, false);
            }
            float[] out = new float[512];
            for (int i = 0; i < 20_000; i++) {
                r.render(out, 0, 256);
            }
            long[] times = new long[5_000];
            for (int i = 0; i < times.length; i++) {
                long t = System.nanoTime();
                r.render(out, 0, 256);
                times[i] = System.nanoTime() - t;
            }
            Arrays.sort(times);
            System.out.printf("PROBE render cost, 1,000 placed voices (%d real), 256 frames: median %.1f us,"
                    + " p99 %.1f us, max %.1f us%n", r.realVoices(), times[times.length / 2] / 1e3,
                times[times.length * 99 / 100] / 1e3, times[times.length - 1] / 1e3);
        }
    }

    /** What a block costs offline: 64 voices of a looping clip at varied pitches, cubic, panned, two buses. */
    @Test
    void renderCostPerBlock() {
        try (AudioRenderer r = AudioRenderer.offline(AudioFormat.stereo(48_000))) {
            AudioClip tone = Clips.sine(44_100, 220, 0.02f, 44_100);
            for (int v = 0; v < 64; v++) {
                r.commands().play(v, tone, 0, 0, 0.1f, 0.8f + v * 0.01f, v % 2 == 0 ? Bus.SFX : Bus.MUSIC, Voices.LOOP,
                    (v % 9 - 4) / 4f, v);
            }
            float[] out = new float[512];
            for (int i = 0; i < 20_000; i++) {
                r.render(out, 0, 256);
            }
            long[] times = new long[5_000];
            for (int i = 0; i < times.length; i++) {
                long t = System.nanoTime();
                r.render(out, 0, 256);
                times[i] = System.nanoTime() - t;
            }
            Arrays.sort(times);
            System.out.printf("PROBE render cost, 64 voices, 256 frames: median %.1f us, p99 %.1f us, max %.1f us"
                    + " (budget 5,333 us)%n", times[times.length / 2] / 1e3, times[times.length * 99 / 100] / 1e3,
                times[times.length - 1] / 1e3);
        }
    }
}

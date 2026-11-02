package com.truesim.engine.audio;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes interleaved float samples as a WAV file: 32-bit float, or 16-bit PCM (clamped, rounded). For tools. */
@AllowAllocation("tools")
public final class WavWriter {

    private WavWriter() {
    }

    /** Writes {@code frames} frames of {@code samples} to {@code file}, {@code bits} 16 or 32 (float). */
    public static void write(Path file, float[] samples, int channels, int rate, int bits) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(bytes(samples, channels, rate, bits));
        }
    }

    /** The WAV file of {@code samples}, {@code bits} 16 or 32 (float). */
    public static byte[] bytes(float[] samples, int channels, int rate, int bits) {
        if (bits != 16 && bits != 32) {
            throw new IllegalArgumentException("16-bit PCM or 32-bit float, not " + bits + " bits");
        }
        int width = bits / 8;
        int data = samples.length * width;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
            .putShort((short) (bits == 32 ? 3 : 1)).putShort((short) channels).putInt(rate)
            .putInt(rate * channels * width).putShort((short) (channels * width)).putShort((short) bits)
            .put("data".getBytes()).putInt(data);
        for (float s : samples) {
            if (bits == 32) {
                b.putFloat(s);
            } else {
                b.putShort((short) Math.round(Math.max(-1, Math.min(1, s)) * 32767));
            }
        }
        return b.array();
    }
}

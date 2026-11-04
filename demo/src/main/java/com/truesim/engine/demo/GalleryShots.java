package com.truesim.engine.demo;

import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Format;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Screenshots of what the gallery's window shows: the presenter's result for the window (encoded for the display, the
 * UI composited), read back each frame and written as a PNG when one is asked for ({@link #take}). The bytes are the
 * window's: an sRGB or UNORM image's 8-bit values as they are, HDR10's 10-bit values scaled to 8 (still PQ encoded),
 * a float image's values clamped.
 */
final class GalleryShots implements Technique {

    private final Key<Image> key;
    private final Path dir;
    private volatile String wanted; // the next shot's file name, or null
    private volatile int written;

    GalleryShots(Key<Image> key, Path dir) {
        this.key = key;
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the next frame read back as {@code name}.png. */
    void take(String name) {
        wanted = name;
    }

    /** How many shots were written. */
    int written() {
        return written;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(key);
    }

    @Override
    public void build(GraphBuilder g) {
        Image image = g.modify(key);
        int width = image.width();
        int height = image.height();
        Format format = image.format();
        g.readback("gallery shot", image.mip(0), (data, frame) -> {
            String name = wanted;
            if (name != null) {
                wanted = null;
                write(data, width, height, format, dir.resolve(name + ".png"));
                written++;
            }
        });
        g.provide(key, image);
    }

    private static void write(MemorySegment data, int width, int height, Format format, Path file) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int bytes = format.bytes();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                long at = ((long) y * width + x) * bytes;
                int r;
                int gr;
                int b;
                if (bytes == 8) {
                    r = unit(Float.float16ToFloat(data.get(ValueLayout.JAVA_SHORT_UNALIGNED, at)));
                    gr = unit(Float.float16ToFloat(data.get(ValueLayout.JAVA_SHORT_UNALIGNED, at + 2)));
                    b = unit(Float.float16ToFloat(data.get(ValueLayout.JAVA_SHORT_UNALIGNED, at + 4)));
                } else if (format == Format.A2B10G10R10_UNORM_PACK32) {
                    int p = data.get(ValueLayout.JAVA_INT_UNALIGNED, at);
                    r = (p & 0x3ff) >> 2;
                    gr = (p >> 10 & 0x3ff) >> 2;
                    b = (p >> 20 & 0x3ff) >> 2;
                } else {
                    int c0 = data.get(ValueLayout.JAVA_BYTE, at) & 0xff;
                    int c1 = data.get(ValueLayout.JAVA_BYTE, at + 1) & 0xff;
                    int c2 = data.get(ValueLayout.JAVA_BYTE, at + 2) & 0xff;
                    boolean bgr = format == Format.B8G8R8A8_UNORM || format == Format.B8G8R8A8_SRGB;
                    r = bgr ? c2 : c0;
                    gr = c1;
                    b = bgr ? c0 : c2;
                }
                out.setRGB(x, y, r << 16 | gr << 8 | b);
            }
        }
        try {
            ImageIO.write(out, "png", file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("shot " + file + " (" + format + ")");
    }

    private static int unit(float v) {
        return (int) Math.max(0, Math.min(255, v * 255 + 0.5f));
    }
}

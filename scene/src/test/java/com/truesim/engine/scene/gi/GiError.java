package com.truesim.engine.scene.gi;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * How far an image is from a GI scene's reference ({@link GiReferences}): per pixel the difference of luminance
 * relative to the reference's (its own, or where darker a twentieth of the reference's mean, at least 0.001, so black
 * pixels do not divide by nothing), summed up as the mean relative error (the mean absolute difference over the
 * reference's mean, at least 0.001), its median, 95th and 99th percentiles per pixel, and the mean absolute difference
 * itself (the measure for references that are black, as a leak test's). Images are linear RGBA floats; references are stored beside their PNGs as
 * raw floats ({@link #write}).
 *
 * @param mean    the mean absolute luminance difference over the reference's mean luminance
 * @param median  the median of the per-pixel relative differences
 * @param p95     their 95th percentile
 * @param p99     their 99th percentile
 * @param bias    the image's mean luminance over the reference's, less 1 (negative: darker); 0 for a black reference
 * @param absolute the mean absolute luminance difference
 */
public record GiError(double mean, double median, double p95, double p99, double bias, double absolute) {

    /** {@code image} against {@code reference}, both {@code width} x {@code height} RGBA floats. */
    public static GiError of(float[] image, float[] reference, int width, int height) {
        int count = width * height;
        double sumReference = 0;
        double sumImage = 0;
        for (int p = 0; p < count; p++) {
            sumReference += luminance(reference, p);
            sumImage += luminance(image, p);
        }
        double meanReference = sumReference / count;
        double floor = Math.max(meanReference / 20, 1.0e-3);
        double[] relative = new double[count];
        double absolute = 0;
        for (int p = 0; p < count; p++) {
            double r = luminance(reference, p);
            double d = Math.abs(luminance(image, p) - r);
            absolute += d;
            relative[p] = d / Math.max(r, floor);
        }
        Arrays.sort(relative);
        return new GiError(absolute / count / Math.max(meanReference, 1.0e-3), relative[count / 2],
            relative[(int) (count * 0.95)], relative[(int) (count * 0.99)],
            meanReference < 1.0e-6 ? 0 : sumImage / sumReference - 1, absolute / count);
    }

    /** Linear rgba's luminance (Rec. 709) at pixel {@code p}. */
    public static double luminance(float[] rgba, int p) {
        return 0.2126 * rgba[4 * p] + 0.7152 * rgba[4 * p + 1] + 0.0722 * rgba[4 * p + 2];
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
            "mean %.1f %%, median %.1f %%, 95th %.1f %%, 99th %.1f %%, bias %+.1f %%, absolute %.4f", 100 * mean,
            100 * median, 100 * p95, 100 * p99, 100 * bias, absolute);
    }

    /** Writes {@code rgba} ({@code width} x {@code height}) as raw floats: width, height, then the values. */
    public static void write(float[] rgba, int width, int height, Path file) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(8 + 4 * rgba.length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(width).putInt(height);
        for (float v : rgba) {
            bytes.putFloat(v);
        }
        Files.write(file, bytes.array());
    }

    /** Reads what {@link #write} wrote: {width, height} into {@code size}, the values returned. */
    public static float[] read(Path file, int[] size) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        size[0] = bytes.getInt();
        size[1] = bytes.getInt();
        float[] rgba = new float[4 * size[0] * size[1]];
        for (int i = 0; i < rgba.length; i++) {
            rgba[i] = bytes.getFloat();
        }
        return rgba;
    }

    /**
     * Writes linear {@code rgba} as an sRGB PNG, times {@code exposure}, compressed above 0.8 (a soft shoulder, so
     * lights keep their color).
     */
    public static void png(float[] rgba, int width, int height, float exposure, Path file) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = y * width + x;
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    double v = Math.max(0, rgba[4 * p + c] * exposure);
                    if (v > 0.8) {
                        double over = v - 0.8;
                        v = 0.8 + 0.2 * over / (0.2 + over);
                    }
                    double srgb = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
                    rgb = rgb << 8 | (int) Math.round(Math.min(1, srgb) * 255);
                }
                image.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }
}

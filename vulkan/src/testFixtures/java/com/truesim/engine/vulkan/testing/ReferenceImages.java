package com.truesim.engine.vulkan.testing;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Screenshots compared with reference images: PNGs kept with a repo's tests ({@code src/test/references}, say), each a
 * test's expected picture. A pixel differs where any channel is off by more than {@code tolerance} (0 to 255; GPUs and
 * drivers round differently, so a few levels); a comparison passes while at most {@code share} of the pixels differ.
 * On a failure the actual image and a difference map (differing pixels red over the reference, dimmed) are written to
 * {@code build/reference-diffs}. A missing reference fails too, unless {@code TRUESIM_UPDATE_REFERENCES=true}, which
 * writes every compared image as its new reference instead (review the PNGs before committing them).
 *
 * <pre>{@code
 * ReferenceImages references = new ReferenceImages(Path.of("src/test/references"));
 * references.assertMatches("glass-over-blue", readback.image(), 3, 0.001);
 * }</pre>
 */
public final class ReferenceImages {

    /** The environment variable that writes compared images as the new references. */
    public static final String UPDATE = "TRUESIM_UPDATE_REFERENCES";

    private final Path references;
    private final Path diffs;
    private final boolean update;

    /** References in {@code references}; differences written to {@code build/reference-diffs}. */
    public ReferenceImages(Path references) {
        this(references, Path.of("build", "reference-diffs"), "true".equals(System.getenv(UPDATE)));
    }

    /** References in {@code references}, differences to {@code diffs}; with {@code update}, writes references. */
    public ReferenceImages(Path references, Path diffs, boolean update) {
        this.references = references;
        this.diffs = diffs;
        this.update = update;
    }

    /**
     * The result of one comparison.
     *
     * @param differing pixels with a channel off by more than the tolerance
     * @param pixels pixels compared
     * @param worst the largest difference of any channel, 0 to 255
     */
    public record Difference(long differing, long pixels, int worst) {

        /** The share of the pixels that differ, 0 to 1. */
        public double share() {
            return pixels == 0 ? 0 : (double) differing / pixels;
        }
    }

    /** How {@code actual} differs from {@code expected} at {@code tolerance}; sizes must match. */
    public static Difference compare(BufferedImage expected, BufferedImage actual, int tolerance) {
        if (expected.getWidth() != actual.getWidth() || expected.getHeight() != actual.getHeight()) {
            throw new AssertionError("Sizes differ: expected " + expected.getWidth() + " x " + expected.getHeight()
                + ", got " + actual.getWidth() + " x " + actual.getHeight());
        }
        long differing = 0;
        int worst = 0;
        for (int y = 0; y < actual.getHeight(); y++) {
            for (int x = 0; x < actual.getWidth(); x++) {
                int d = channelDifference(expected.getRGB(x, y), actual.getRGB(x, y));
                worst = Math.max(worst, d);
                differing += d > tolerance ? 1 : 0;
            }
        }
        return new Difference(differing, (long) actual.getWidth() * actual.getHeight(), worst);
    }

    /**
     * Fails unless {@code actual} matches reference {@code name} ({@code name.png}): at most {@code share} of its
     * pixels off by more than {@code tolerance}. Writes the reference instead when updating. Returns the difference.
     */
    public Difference assertMatches(String name, BufferedImage actual, int tolerance, double share) {
        Path reference = references.resolve(name + ".png");
        try {
            if (update) {
                Files.createDirectories(references);
                ImageIO.write(actual, "png", reference.toFile());
                return new Difference(0, (long) actual.getWidth() * actual.getHeight(), 0);
            }
            if (!Files.isRegularFile(reference)) {
                write(name + ".actual", actual);
                throw new AssertionError("No reference image " + reference + " (run with " + UPDATE
                    + "=true to write it; the image is in " + diffs + ")");
            }
            BufferedImage expected = ImageIO.read(reference.toFile());
            Difference difference = compare(expected, actual, tolerance);
            if (difference.share() > share) {
                write(name + ".actual", actual);
                write(name + ".diff", diff(expected, actual, tolerance));
                throw new AssertionError(String.format(
                    "%s: %d of %d pixels (%.3f%%) differ by more than %d (worst %d), over the %.3f%% allowed; see %s",
                    name, difference.differing(), difference.pixels(), 100 * difference.share(), tolerance,
                    difference.worst(), 100 * share, diffs));
            }
            return difference;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String name, BufferedImage image) throws IOException {
        Files.createDirectories(diffs);
        ImageIO.write(image, "png", diffs.resolve(name + ".png").toFile());
    }

    /** The reference dimmed, with the pixels differing by more than {@code tolerance} in red. */
    private static BufferedImage diff(BufferedImage expected, BufferedImage actual, int tolerance) {
        BufferedImage out = new BufferedImage(actual.getWidth(), actual.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < actual.getHeight(); y++) {
            for (int x = 0; x < actual.getWidth(); x++) {
                int e = expected.getRGB(x, y);
                boolean off = channelDifference(e, actual.getRGB(x, y)) > tolerance;
                int dim = (((e >> 16) & 0xff) + ((e >> 8) & 0xff) + (e & 0xff)) / 9;
                out.setRGB(x, y, off ? 0xff0000 : dim << 16 | dim << 8 | dim);
            }
        }
        return out;
    }

    private static int channelDifference(int a, int b) {
        int worst = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            worst = Math.max(worst, Math.abs((a >>> shift & 0xff) - (b >>> shift & 0xff)));
        }
        return worst;
    }
}

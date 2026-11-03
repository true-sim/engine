package com.truesim.engine.audio;

import com.truesim.scene.audio.HrtfSet;
import io.jhdf.HdfFile;
import io.jhdf.api.Attribute;
import io.jhdf.api.Dataset;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts a SOFA file of the {@code SimpleFreeFieldHRIR} convention (AES69) to the {@code .hrtf} format (§2.9,
 * decision 2): HDF5 is read here, at build time, through jHDF, and never at runtime. The responses are prepared by
 * {@link HrtfConversion} (onsets, interaural time differences, minimum phase, thinning).
 *
 * <pre>
 * ./gradlew :engine-audio:convertHrtf -Ptruesim.audio.sofa=D1_48K_24bit_256tap_FIR_SOFA.sofa \
 *     -Ptruesim.audio.hrtf=out.hrtf [-Ptruesim.audio.directions=2000] [-Ptruesim.audio.taps=128] \
 *     [-Ptruesim.audio.credit="..."]
 * </pre>
 */
public final class SofaConverter {

    private SofaConverter() {
    }

    /** Arguments: the SOFA file, the {@code .hrtf} to write, directions to keep (0 all), taps, the credit. */
    public static void main(String[] args) throws IOException {
        Path sofa = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int keep = Integer.parseInt(args[2]);
        int taps = Integer.parseInt(args[3]);
        String credit = args[4];
        try (HdfFile file = new HdfFile(sofa)) {
            String convention = text(file.getAttribute("SOFAConventions"));
            if (!convention.equals("SimpleFreeFieldHRIR")) {
                throw new IOException(sofa + " is " + convention + ", not SimpleFreeFieldHRIR");
            }
            Dataset ir = file.getDatasetByPath("Data.IR");
            Dataset positions = file.getDatasetByPath("SourcePosition");
            Dataset rate = file.getDatasetByPath("Data.SamplingRate");
            String type = text(positions.getAttribute("Type"));
            if (!type.equalsIgnoreCase("spherical")) {
                throw new IOException("Source positions are " + type + ", not spherical");
            }
            double[][][] data = (double[][][]) ir.getData();
            double[][] where = (double[][]) positions.getData();
            int sampleRate = (int) Math.round(((double[]) rate.getData())[0]);
            int count = data.length;
            float[][] left = new float[count][];
            float[][] right = new float[count][];
            for (int m = 0; m < count; m++) {
                left[m] = floats(data[m][0]);
                right[m] = floats(data[m][1]);
            }
            System.out.printf("%s: %d directions, %d taps at %d Hz (%s)%n", sofa.getFileName(), count,
                data[0][0].length, sampleRate, text(file.getAttribute("Title")));
            HrtfSet set = HrtfConversion.convert(sampleRate, where, left, right, taps, keep, credit);
            Files.createDirectories(out.toAbsolutePath().getParent());
            try (OutputStream o = Files.newOutputStream(out)) {
                set.write(o);
            }
            System.out.printf("%s: %d directions, %d taps, %d bytes%n", out.getFileName(), set.count(), set.taps(),
                Files.size(out));
        }
    }

    private static String text(Attribute attribute) {
        Object value = attribute == null ? "" : attribute.getData();
        return value instanceof String[] strings ? strings[0] : String.valueOf(value);
    }

    private static float[] floats(double[] d) {
        float[] f = new float[d.length];
        for (int i = 0; i < d.length; i++) {
            f[i] = (float) d[i];
        }
        return f;
    }
}

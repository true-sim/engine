package com.truesim.engine.render;

/**
 * The tone curves as their published reference code computes them, ported line by line to doubles, for the tests to
 * check encode.slang against: Narkowicz's ACES fit (his blog, 2016), Hill's ACES fit (BakingLab, ACES.hlsl), AgX
 * (Wrensch's minimal AgX, iolite, after Sobotka, with Blender's looks), Khronos PBR Neutral (KhronosGroup/ToneMapping,
 * PBR_Neutral.glsl), Uchimura's GT curve (CEDEC 2017, his shader) and Hable's Uncharted 2 curve (filmicworlds.com).
 */
final class ToneReference {

    private ToneReference() {
    }

    static double[] reinhard(double[] c) {
        return new double[] {c[0] / (1 + c[0]), c[1] / (1 + c[1]), c[2] / (1 + c[2])};
    }

    static double[] reinhardExtended(double[] c, double white) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Math.min(1, c[i] * (1 + c[i] / (white * white)) / (1 + c[i]));
        }
        return out;
    }

    static double[] acesNarkowicz(double[] c) {
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double x = c[i] * 0.6;
            out[i] = saturate((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14));
        }
        return out;
    }

    private static final double[][] ACES_INPUT = {
        {0.59719, 0.35458, 0.04823},
        {0.07600, 0.90834, 0.01566},
        {0.02840, 0.13383, 0.83777}};
    private static final double[][] ACES_OUTPUT = {
        {1.60475, -0.53108, -0.07367},
        {-0.10208, 1.10813, -0.00605},
        {-0.00327, -0.07276, 1.07602}};

    static double[] acesHill(double[] c) {
        double[] v = mul(ACES_INPUT, c);
        for (int i = 0; i < 3; i++) {
            double a = v[i] * (v[i] + 0.0245786) - 0.000090537;
            double b = v[i] * (0.983729 * v[i] + 0.4329510) + 0.238081;
            v[i] = a / b;
        }
        v = mul(ACES_OUTPUT, v);
        return new double[] {saturate(v[0]), saturate(v[1]), saturate(v[2])};
    }

    // GLSL mat3(...) is filled column by column: these are the reference's numbers, as rows of the transposed matrix.
    private static final double[][] AGX_COLUMNS = {
        {0.842479062253094, 0.0423282422610123, 0.0423756549057051},
        {0.0784335999999992, 0.878468636469772, 0.0784336},
        {0.0792237451477643, 0.0791661274605434, 0.879142973793104}};
    private static final double[][] AGX_INVERSE_COLUMNS = {
        {1.19687900512017, -0.0528968517574562, -0.0529716355144438},
        {-0.0980208811401368, 1.15190312990417, -0.0980434501171241},
        {-0.0990297440797205, -0.0989611768448433, 1.15107367264116}};

    /** AgX, then linearized by the 2.2 power the reference's commented-out EOTF names; look 0 base, 1 punchy, 2 golden. */
    static double[] agx(double[] c, int look) {
        double[] v = mulColumns(AGX_COLUMNS, c);
        double minEv = -12.47393;
        double maxEv = 4.026069;
        for (int i = 0; i < 3; i++) {
            double ev = Math.min(maxEv, Math.max(minEv, Math.log(Math.max(v[i], 1e-10)) / Math.log(2)));
            double x = (ev - minEv) / (maxEv - minEv);
            double x2 = x * x;
            double x4 = x2 * x2;
            v[i] = 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4 - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
        }
        if (look != 0) {
            double luma = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
            double[] slope = look == 2 ? new double[] {1.0, 0.9, 0.5} : new double[] {1, 1, 1};
            double power = look == 2 ? 0.8 : 1.35;
            double saturation = look == 2 ? 0.8 : 1.4;
            for (int i = 0; i < 3; i++) {
                v[i] = Math.pow(Math.max(v[i] * slope[i], 0), power);
                v[i] = luma + saturation * (v[i] - luma);
            }
        }
        v = mulColumns(AGX_INVERSE_COLUMNS, v);
        return new double[] {Math.pow(saturate(v[0]), 2.2), Math.pow(saturate(v[1]), 2.2), Math.pow(saturate(v[2]),
            2.2)};
    }

    /** PBR Neutral; {@code top} 1 is the reference, more its shoulder raised for HDR (the engine's form). */
    static double[] pbrNeutral(double[] in, double top) {
        double[] color = in.clone();
        double startCompression = 0.8 - 0.04;
        double desaturation = 0.15;
        double x = Math.min(color[0], Math.min(color[1], color[2]));
        double offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
        for (int i = 0; i < 3; i++) {
            color[i] -= offset;
        }
        double peak = Math.max(color[0], Math.max(color[1], color[2]));
        if (peak < startCompression) {
            return color;
        }
        double d = top - startCompression;
        double newPeak = top - d * d / (peak + d - startCompression);
        double g = 1 - 1 / (desaturation * (peak - newPeak) + 1);
        for (int i = 0; i < 3; i++) {
            color[i] *= newPeak / peak;
            color[i] = color[i] * (1 - g) + newPeak * g;
        }
        return color;
    }

    /** Uchimura's curve with its reference parameters and maximum {@code P}. */
    static double gt(double x, double P) {
        double a = 1;
        double m = 0.22;
        double l = 0.4;
        double c = 1.33;
        double b = 0;
        double l0 = (P - m) * l / a;
        double S0 = m + l0;
        double S1 = m + a * l0;
        double C2 = a * P / (P - S1);
        double Lx = m + a * (x - m);
        double Tx = m * Math.pow(x / m, c) + b;
        double Sx = P - (P - S1) * Math.exp(-(C2 * (x - S0) / P));
        double w0 = 1 - smooth(x, 0, m);
        double w2 = x < m + l0 ? 0 : 1;
        double w1 = 1 - w0 - w2;
        return Tx * w0 + Lx * w1 + Sx * w2;
    }

    static double[] gt(double[] c) {
        return new double[] {saturate(gt(c[0], 1)), saturate(gt(c[1], 1)), saturate(gt(c[2], 1))};
    }

    static double[] hable(double[] c) {
        double white = hable(11.2);
        return new double[] {saturate(hable(2 * c[0]) / white), saturate(hable(2 * c[1]) / white),
            saturate(hable(2 * c[2]) / white)};
    }

    private static double hable(double x) {
        double A = 0.15;
        double B = 0.50;
        double C = 0.10;
        double D = 0.20;
        double E = 0.02;
        double F = 0.30;
        return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
    }

    /** The sRGB curve (IEC 61966-2-1). */
    static double srgb(double c) {
        c = saturate(c);
        return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }

    /** SMPTE ST 2084 of {@code nits}. */
    static double pq(double nits) {
        double y = Math.pow(nits / 10000, 0.1593017578125);
        return Math.pow((0.8359375 + 18.8515625 * y) / (1 + 18.6875 * y), 78.84375);
    }

    /** Rec. 709 to Rec. 2020 primaries, as encode.slang has them (ITU-R BT.2087). */
    static double[] rec2020(double[] c) {
        return mul(new double[][] {
            {0.6274040, 0.3292820, 0.0433136},
            {0.0690970, 0.9195400, 0.0113612},
            {0.0163916, 0.0880132, 0.8955950}}, c);
    }

    private static double smooth(double x, double e0, double e1) {
        if (x <= e0) {
            return 0;
        }
        if (x >= e1) {
            return 1;
        }
        double a = (x - e0) / (e1 - e0);
        return a * a * (3 - 2 * a);
    }

    private static double saturate(double x) {
        return Math.min(1, Math.max(0, x));
    }

    private static double[] mul(double[][] rows, double[] v) {
        double[] out = new double[3];
        for (int r = 0; r < 3; r++) {
            out[r] = rows[r][0] * v[0] + rows[r][1] * v[1] + rows[r][2] * v[2];
        }
        return out;
    }

    private static double[] mulColumns(double[][] columns, double[] v) {
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                out[r] += columns[c][r] * v[c];
            }
        }
        return out;
    }
}

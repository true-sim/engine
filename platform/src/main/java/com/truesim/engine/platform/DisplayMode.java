package com.truesim.engine.platform;

/**
 * A resolution and refresh rate a display supports, e.g. for exclusive fullscreen. Sizes are in screen coordinates;
 * multiply by the pixel density for pixels.
 */
public record DisplayMode(int displayId, int width, int height, float pixelDensity, float refreshRate,
    int refreshNumerator, int refreshDenominator, int format) {

    /** Width in pixels. */
    public int pixelWidth() {
        return Math.round(width * pixelDensity);
    }

    /** Height in pixels. */
    public int pixelHeight() {
        return Math.round(height * pixelDensity);
    }
}

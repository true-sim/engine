// The layout of a UI primitive as ui-core's Primitive defines it (com.truesim.ui.core.draw.Primitive): 16 words,
// read by the UI shader through a pointer. Primitive is the one definition; the build makes these constants Java
// (PrimitiveConstants) and a test checks them against it, so the two cannot drift apart.
module primitive;

/** Words per primitive. */
public static const uint WORDS = 16;

/** Kinds (header bits 0-7). */
public static const uint EMPTY = 0;
public static const uint BOX = 1;
public static const uint SHADOW = 2;
public static const uint GLYPH = 3;
public static const uint IMAGE = 4;
public static const uint NINE_SLICE = 5;
public static const uint LINE = 6;
public static const uint KINDS = 7;

/** Flags (header bits 8-15): a box filled by a linear gradient; a glyph of hinted coverage, not an MTSDF. */
public static const uint FLAG_GRADIENT = 1;
public static const uint FLAG_RASTER = 2;

/** Word offsets. */
public static const uint HEADER = 0;
public static const uint X0 = 1;
public static const uint Y0 = 2;
public static const uint X1 = 3;
public static const uint Y1 = 4;
public static const uint COLOR_A = 5;
public static const uint COLOR_B = 7;
public static const uint RADII = 9;
public static const uint UV = 9;
public static const uint WIDTH = 11;
public static const uint TEXTURE = 11;
public static const uint BORDER_COLOR = 12;
public static const uint PIXEL_RANGE = 12;
public static const uint OUTLINE = 13;
public static const uint INSETS = 12;
public static const uint GRADIENT_ANGLE = 14;
public static const uint WEIGHT = 14;
public static const uint SOURCE_INSETS = 14;
public static const uint RESERVED = 15;

/** Floats per clip rectangle: x0, y0, x1, y1, corner radius (ui-core's DrawList.CLIP_FLOATS). */
public static const uint CLIP_FLOATS = 5;

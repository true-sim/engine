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
public static const uint TRIANGLE = 7;
public static const uint KINDS = 8;

/**
 * Flags (header bits 8-15): a box filled by a linear gradient; a glyph of hinted coverage, not an MTSDF; a glyph of
 * colour texels (an emoji bitmap on an sRGB page, straight alpha).
 */
public static const uint FLAG_GRADIENT = 1;
public static const uint FLAG_RASTER = 2;
public static const uint FLAG_COLOR = 4;
/**
 * Flag of a solid BOX, a LINE or a TRIANGLE: depth tested against the scene drawn under the UI (a gizmo's handle). Word
 * DEPTH holds its view depth (distance along the view's forward axis) at its first corner as a float whose low 8 mantissa
 * bits are its occluded opacity (unorm8); word DEPTH_B a line's depth at its second end (a float) or a triangle's depths
 * at b and c over a's, minus 1 (two halves). Where the scene is nearer, its colour is times the occluded opacity.
 */
public static const uint FLAG_DEPTH = 128;

/**
 * Flags of a TRIANGLE (header bits 8-15): which of its edges are anti-aliased (a shape's outline); the others are hard,
 * half-open, so triangles sharing them never cover a pixel twice.
 */
public static const uint EDGE_AB = 1;
public static const uint EDGE_BC = 2;
public static const uint EDGE_CA = 4;

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
public static const uint TRANSFORM = 15;
/** A TRIANGLE's corners a, b, c as floats, in the space of its bounding box (words 1-4). */
public static const uint XA = 9;
public static const uint YA = 10;
public static const uint XB = 11;
public static const uint YB = 12;
public static const uint XC = 13;
public static const uint YC = 14;
/** A depth tested primitive's depth words (colour B's, which its kinds do not use otherwise). */
public static const uint DEPTH = 7;
public static const uint DEPTH_B = 8;

/**
 * Floats per clip record (ui-core's DrawList.CLIP_FLOATS): x0, y0, x1, y1, corner radius (in the space of its
 * transform), the offset x, y added to its primitives' coordinates (a scrolled region's scroll), the index of the
 * transform its rectangle is drawn by.
 */
public static const uint CLIP_FLOATS = 8;

/**
 * Floats per transform table entry (ui-core's DrawList.TRANSFORM_FLOATS, 32 bytes): scale, offset x, offset y, opacity,
 * four reserved. A primitive draws at scale * (p + its clip's offset) + offset, its sizes times the scale, its colour
 * times the opacity; entry 0 is the identity.
 */
public static const uint TRANSFORM_FLOATS = 8;

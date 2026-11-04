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
/** Kinds over vertices of the list's vertex table, expanded into a quad per segment or column (draws of their own). */
public static const uint PATH = 8;
public static const uint COLUMNS = 9;
public static const uint KINDS = 10;

/**
 * Flags (header bits 8-15): a box filled by a linear gradient; a glyph of hinted coverage, not an MTSDF; a glyph of
 * colour texels (an emoji bitmap on an sRGB page, straight alpha).
 */
public static const uint FLAG_GRADIENT = 1;
public static const uint FLAG_RASTER = 2;
public static const uint FLAG_COLOR = 4;

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

/** Words of the kinds over vertices (ui-core's Primitive). */
public static const uint FIRST_VERTEX = 7;
public static const uint VERTEX_COUNT = 8;
public static const uint MITER_LIMIT = 9;
public static const uint BASELINE = 9;
public static const uint DASH_PHASE = 10;
public static const uint COLUMN_X = 10;
public static const uint DASHES = 12;
public static const uint COLUMN_STEP = 12;
public static const uint STROKE = 14;

/** A PATH's joins (STROKE bits 0-1) and caps (bits 2-3); a COLUMNS' flags. */
public static const uint JOIN_NONE = 0;
public static const uint JOIN_MITER = 1;
public static const uint JOIN_BEVEL = 2;
public static const uint JOIN_ROUND = 3;
public static const uint CAP_BUTT = 0;
public static const uint CAP_SQUARE = 4;
public static const uint CAP_ROUND = 8;
public static const uint COLUMNS_FILL = 16;
public static const uint COLUMNS_STEPS = 32;

/**
 * Floats per vertex of the vertex table (ui-core's DrawList.VERTEX_FLOATS): a path's x, y, length along it and a spare
 * float; a pixel column's first, last, lowest and highest y.
 */
public static const uint VERTEX_FLOATS = 4;

// The UI's primitives (ui-core's draw list, laid out as ui/primitive.slang says) drawn into the UI image: premultiplied
// alpha, linear, 1.0 paper white. Vertex pulling: six vertices per primitive, SV_VertexID giving the primitive and
// the corner, no vertex buffers. Each primitive's quad covers what it may touch (a box's anti-aliased edge, a shadow's
// blur, a line's width), cut to its clip rectangle's bounds; the fragment shader works out coverage from the pixel's
// position by the primitive's kind (a signed distance for boxes, lines and glyphs), clipped by the clip's rounded
// rectangle. EMPTY primitives (padding of an element's range) are degenerate: no pixels.
//
// Where a primitive draws: its coordinates plus its clip's offset (a scrolled region records its content once,
// unscrolled, and scrolling moves the offset), then its transform table entry's scale, rotation and offset (an
// element's animated scale or drawn offset: the animation rewrites the entry, not the primitives). A local entry (one
// naming a parent: rotated text) places the coordinates first, then the clip's offset is added and the parent places
// them. The vertices are placed so (a rotated primitive's quad turned with it, cut by its clip at the pixel only); the
// kinds' coverage is worked out at the pixel mapped back into the primitive's own coordinates, distances scaled to
// pixels by the scale, and the colour times the opacity. A clip's rectangle is drawn by its own entry's scale and
// offset (its owner's: a scaled or moved subtree is cut where it draws; clips are not rotated) and tested at the pixel
// itself. Columns (pixel columns) are never rotated.
//
// One draw per layer runs fragmentMain, which branches on the kind (the "uber" path); the per-kind path draws runs of
// one kind with that kind's own fragment entry point (boxMain, glyphMain, ...), in list order. The kinds over vertices
// (PATH, COLUMNS: chart lines and envelopes) are drawn by draws of their own, a quad per segment or column (below).
//
// Depth tested primitives (FLAG_DEPTH: a gizmo's handles, projections of 3D shapes) are faded per pixel where the
// scene drawn under the UI in this frame is nearer (design UI26): their view depth at the pixel (interpolated from
// their corners', ui/primitive.slang) against the scene depth's texel under it (reverse depth turned into a view depth
// by the camera's projection), their colour times their occluded opacity where behind. The view's depth is read in the
// same frame it was drawn: no delay, exact where an occluder moved.
import bindless;
import ui.primitive;

struct UiPush
{
    uint* primitives;   // the layer's primitives, WORDS words each
    float* clips;       // its clips, CLIP_FLOATS floats each (rectangle, radius, offset, transform); 0 clips nothing
    uint* textures;     // the sampled slot of each UI texture id
    float* transforms;  // its transform table, TRANSFORM_FLOATS floats each (scale, offset, opacity); 0 the identity
    uint first;         // the first primitive drawn: 0 for a whole layer, a run's first for per-kind draws
    float scaleX;       // UI pixels per pixel of the image
    float scaleY;
    float toClipX;      // 2 / the UI's width in pixels
    float toClipY;      // 2 / its height
    float originX;      // the UI pixel at the image's top left: 0, or a docking OS window's region
    float originY;
    float* world;       // a world panel in the 3D view: per view 16 floats (column-major), pixels to clip
    uint depth;         // the scene depth's sampled slot plus 1, 0 for none (depth tested primitives draw unfaded)
    float depthScaleX;  // its texels per pixel of the image (its render region over the image's size)
    float depthScaleY;
    uint depthMaxX;     // its render region's last texel
    uint depthMaxY;
    float depthNear;    // under perspective: the near plane (reverse depth, infinite far: view depth = near / depth)
    float depthRange;   // under an orthographic view: its depth range (view depth = (1 - depth) * range); else 0
    float depthBias;    // how much farther than the scene (relative) a primitive must be to be hidden
    float4* vertices;   // the layer's vertex table (VERTEX_FLOATS floats each): paths' points, envelopes' columns
};

[[vk::push_constant]]
ConstantBuffer<UiPush> push;

struct VertexOut
{
    float4 position : SV_Position;
    nointerpolation uint primitive : PRIMITIVE;
};

/** Two triangles: corners top left, top right, bottom left, then bottom left, top right, bottom right. */
static const uint CORNERS[6] = { 0, 1, 2, 2, 1, 3 };

/**
 * Where a transform table entry places its primitives: own coordinates p map to
 * outer(inner(p) + clip offset), each stage scale * R * q + offset (R a rotation, (cos, sin)); the inner stage is a
 * local entry's own (its parent the outer one), the identity otherwise. Colours times the opacity.
 */
struct Transform
{
    float scale;        // pixels a unit of the primitive's own coordinates draws at (both stages)
    float2 offset;      // the outer stage's offset
    float opacity;      // the entry's times its parent's
    float outerScale;
    float2 outerTurn;   // cos, sin
    float innerScale;
    float2 innerTurn;
    float2 innerOffset;
    bool turns;         // a rotation in either stage
};

/** `p` turned by `r` (cos, sin): clockwise on the screen, y being down. */
float2 turn(float2 r, float2 p)
{
    return float2(r.x * p.x - r.y * p.y, r.y * p.x + r.x * p.y);
}

/** `p` turned back by `r`. */
float2 turnBack(float2 r, float2 p)
{
    return float2(r.x * p.x + r.y * p.y, r.x * p.y - r.y * p.x);
}

/** Entry `index` of the transform table; entry 0, the identity of nearly every primitive, without a memory read. */
Transform transformAt(uint index)
{
    Transform x;
    x.scale = 1;
    x.offset = float2(0, 0);
    x.opacity = 1;
    x.outerScale = 1;
    x.outerTurn = float2(1, 0);
    x.innerScale = 1;
    x.innerTurn = float2(1, 0);
    x.innerOffset = float2(0, 0);
    x.turns = false;
    if (index == 0)
    {
        return x;
    }
    float* t = push.transforms + index * TRANSFORM_FLOATS;
    uint parent = uint(t[TRANSFORM_PARENT]);
    float* o = t;
    if (parent != 0)
    {
        x.innerScale = t[0];
        x.innerOffset = float2(t[1], t[2]);
        x.innerTurn = float2(t[TRANSFORM_COS], t[TRANSFORM_SIN]);
        o = push.transforms + parent * TRANSFORM_FLOATS;
        x.opacity = t[3] * o[3];
    }
    else
    {
        x.opacity = t[3];
    }
    x.outerScale = o[0];
    x.offset = float2(o[1], o[2]);
    x.outerTurn = float2(o[TRANSFORM_COS], o[TRANSFORM_SIN]);
    x.scale = x.innerScale * x.outerScale;
    x.turns = x.innerTurn.y != 0 || x.innerTurn.x != 1 || x.outerTurn.y != 0 || x.outerTurn.x != 1;
    return x;
}

/** Where own point `p` of a primitive placed by `t`, its clip's offset `clipOffset`, draws (UI pixels). */
float2 place(Transform t, float2 p, float2 clipOffset)
{
    float2 q = turn(t.innerTurn, p) * t.innerScale + t.innerOffset + clipOffset;
    return turn(t.outerTurn, q) * t.outerScale + t.offset;
}

/** A coordinate placed by `t` without its rotation (columns, never rotated): x (or y) of `place`. */
float placeX(Transform t, float x, float clipOffset)
{
    return (x * t.innerScale + t.innerOffset.x + clipOffset) * t.outerScale + t.offset.x;
}

/** As `placeX`, y. */
float placeY(Transform t, float y, float clipOffset)
{
    return (y * t.innerScale + t.innerOffset.y + clipOffset) * t.outerScale + t.offset.y;
}

float word(uint* p, uint at)
{
    return asfloat(p[at]);
}

/**
 * A half's bits as a float, by hand: f16tof32 needs the Float16 capability, which devices may not have. The bits
 * moved into a float's place and scaled by 2^112 (the exponents' bias apart); subnormal halves may flush to 0, and
 * infinities are not kept, neither of which a UI's colours and radii hold.
 */
float halfToFloat(uint h)
{
    float magnitude = asfloat((h & 0x7fffu) << 13) * 5.192296858534828e33;
    return (h & 0x8000u) != 0 ? -magnitude : magnitude;
}

/** Two halves of a word, the low one first. */
float2 halves(uint w)
{
    return float2(halfToFloat(w & 0xffff), halfToFloat(w >> 16));
}

/** Two unorm16 of a word, the low one first. */
float2 unorms(uint w)
{
    return float2(w & 0xffff, w >> 16) / 65535.0;
}

/** A colour at words `at`, `at + 1`: linear, straight alpha, as halves; returned premultiplied. */
float4 color(uint* p, uint at)
{
    float2 rg = halves(p[at]);
    float2 ba = halves(p[at + 1]);
    float a = saturate(ba.y);
    return float4(float3(rg, ba.x) * a, a);
}

/** The signed distance from `p` (relative to the centre) to a box of half size `b` with corner radius `r`. */
float roundedBox(float2 p, float2 b, float r)
{
    float2 q = abs(p) - b + r;
    return length(max(q, 0)) + min(max(q.x, q.y), 0) - r;
}

/** The radius of the corner `p` (relative to the centre, y down) is nearest to: top left, top right, bottom right, left. */
float radiusAt(uint* p, float2 at)
{
    float2 top = halves(p[RADII]);
    float2 bottom = halves(p[RADII + 1]);
    return at.x < 0 ? (at.y < 0 ? top.x : bottom.y) : (at.y < 0 ? top.y : bottom.x);
}

float median(float r, float g, float b)
{
    return max(min(r, g), min(max(r, g), b));
}

/** Clip `clip`'s rectangle where it cuts (its transform applied): x0, y0, x1, y1; its radius into `radius`. */
float4 clipBounds(uint clip, out float radius)
{
    float* c = push.clips + clip * CLIP_FLOATS;
    Transform t = transformAt(uint(c[7]));
    radius = c[4] * t.scale;
    return float4(float2(c[0], c[1]) * t.scale + t.offset, float2(c[2], c[3]) * t.scale + t.offset);
}

/** The offset clip `clip` adds to its primitives' coordinates (0 for the clip that clips nothing). */
float2 clipOffset(uint header)
{
    uint clip = header >> 16;
    if (clip == 0)
    {
        return float2(0, 0);
    }
    float* c = push.clips + clip * CLIP_FLOATS;
    return float2(c[5], c[6]);
}

// ------------------------------------------------------------------------------------------------------------ vertices

[shader("vertex")]
VertexOut vertexMain(uint vertex : SV_VulkanVertexID)
{
    uint index = push.first + vertex / 6;
    uint corner = CORNERS[vertex % 6];
    uint* p = push.primitives + index * WORDS;
    uint header = p[HEADER];
    uint kind = header & 0xff;
    VertexOut out;
    out.primitive = index;
    if (kind == EMPTY || kind >= KINDS || kind == PATH || kind == COLUMNS) // those over vertices: draws of their own
    {
        out.position = float4(-2, -2, 0, 1); // every corner at one point: no area, no pixels
        return out;
    }
    Transform t = transformAt(p[TRANSFORM]);
    float2 offset = clipOffset(header);
    float2 a = place(t, float2(word(p, X0), word(p, Y0)), offset);
    float2 b = place(t, float2(word(p, X1), word(p, Y1)), offset);
    float2 at;
    if (kind == LINE)
    {
        // A rectangle along the segment, its half width and ends grown by half a pixel: coverage (0.5 minus the
        // distance past the edge) is 0 from there on, so nothing further out is shaded.
        float2 d = b - a;
        float len = length(d);
        float2 along = len > 0 ? d / len : float2(1, 0);
        float2 normal = float2(-along.y, along.x);
        float extent = max(word(p, WIDTH) * t.scale, 1) * 0.5 + 0.5;
        at = ((corner & 1) != 0 ? b + along * 0.5 : a - along * 0.5)
            + normal * ((corner & 2) != 0 ? extent : -extent);
    }
    else if (t.turns)
    {
        // a rotated quad: the box's corners (in its own coordinates, grown by what it may touch) placed and turned;
        // its clip cuts at the pixel
        float2 lo = min(float2(word(p, X0), word(p, Y0)), float2(word(p, X1), word(p, Y1)));
        float2 hi = max(float2(word(p, X0), word(p, Y0)), float2(word(p, X1), word(p, Y1)));
        float grow = kind == SHADOW ? 3 * max(word(p, WIDTH), 0)
            : kind == BOX || kind == TRIANGLE ? 0.5 / max(t.scale, 1e-6) : 0;
        lo -= grow;
        hi += grow;
        at = place(t, float2((corner & 1) != 0 ? hi.x : lo.x, (corner & 2) != 0 ? hi.y : lo.y), offset);
    }
    else
    {
        float2 lo = min(a, b);
        float2 hi = max(a, b);
        if (kind == SHADOW)
        {
            float grow = 3 * max(word(p, WIDTH) * t.scale, 0); // the blur's reach
            lo -= grow;
            hi += grow;
        }
        else if (kind == BOX || kind == TRIANGLE)
        {
            lo -= 0.5; // the anti-aliased edge's outer half pixel (a triangle's quad is its bounding box)
            hi += 0.5;
        }
        uint clip = header >> 16;
        if (clip != 0)
        {
            float radius;
            float4 bounds = clipBounds(clip, radius);
            lo = max(lo, bounds.xy);
            hi = min(hi, bounds.zw);
            if (any(lo >= hi))
            {
                out.position = float4(-2, -2, 0, 1); // clipped away
                return out;
            }
        }
        at = float2((corner & 1) != 0 ? hi.x : lo.x, (corner & 2) != 0 ? hi.y : lo.y);
    }
    out.position = float4((at.x - push.originX) * push.toClipX - 1, (at.y - push.originY) * push.toClipY - 1, 0, 1);
    return out;
}

// ------------------------------------------------------------------------------------------------------------- kinds
// Each kind works at `px`, the pixel in the primitive's own coordinates; `k` is the pixels one of its units draws at
// (its transform's scale), by which distances become pixels for anti-aliasing.

/** A rounded box: fill (solid or a linear gradient) and border, anti-aliased by its signed distance. */
float4 box(uint* p, uint header, float2 px, float k)
{
    float2 lo = float2(word(p, X0), word(p, Y0));
    float2 hi = float2(word(p, X1), word(p, Y1));
    float2 extent = (hi - lo) * 0.5;
    float2 at = px - (lo + hi) * 0.5;
    float r = clamp(radiusAt(p, at), 0, min(extent.x, extent.y));
    float outer = saturate(0.5 - roundedBox(at, extent, r) * k);
    float border = word(p, WIDTH);
    float inner = outer;
    if (border > 0)
    {
        float2 innerHalf = extent - border;
        inner = all(innerHalf > 0) ? saturate(0.5 - roundedBox(at, innerHalf, max(r - border, 0)) * k) : 0;
    }
    float4 fill = color(p, COLOR_A);
    if (((header >> 8) & FLAG_GRADIENT) != 0)
    {
        float angle = word(p, GRADIENT_ANGLE);
        float2 direction = float2(cos(angle), sin(angle));
        float reach = abs(extent.x * direction.x) + abs(extent.y * direction.y);
        float t = reach > 0 ? saturate(dot(at, direction) / reach * 0.5 + 0.5) : 0.5;
        fill = lerp(fill, color(p, COLOR_B), t);
    }
    return fill * inner + color(p, BORDER_COLOR) * (outer - inner);
}

float gaussian(float x, float sigma)
{
    return exp(-(x * x) / (2 * sigma * sigma)) / (2.5066282746 * sigma);
}

float2 erf(float2 x)
{
    float2 s = sign(x);
    float2 a = abs(x);
    float2 y = 1 + (0.278393 + (0.230389 + 0.078108 * (a * a)) * a) * a;
    y *= y;
    return s - s / (y * y);
}

/** The blurred rounded box's row at height `y`, integrated along x in closed form (Evan Wallace). */
float shadowRow(float x, float y, float sigma, float r, float2 extent)
{
    float delta = min(extent.y - r - abs(y), 0);
    float curved = extent.x - r + sqrt(max(0, r * r - delta * delta));
    float2 integral = 0.5 + 0.5 * erf((x + float2(-curved, curved)) * (0.70710678 / sigma));
    return integral.y - integral.x;
}

/**
 * A rounded box blurred by a Gaussian of sigma pixels: Evan Wallace's fast rounded rectangle shadows, closed form
 * along x, four samples along y. Its corner's radius is the one nearest the pixel. The blur's coverage does not depend
 * on the scale: it is worked out in the primitive's own units.
 */
float4 shadow(uint* p, float2 px, float k)
{
    float2 lo = float2(word(p, X0), word(p, Y0));
    float2 hi = float2(word(p, X1), word(p, Y1));
    float2 extent = (hi - lo) * 0.5;
    float2 at = px - (lo + hi) * 0.5;
    float r = clamp(radiusAt(p, at), 0, min(extent.x, extent.y));
    float sigma = word(p, WIDTH);
    if (sigma * k < 0.25)
    {
        return color(p, COLOR_A) * saturate(0.5 - roundedBox(at, extent, r) * k); // no blur: the box itself
    }
    float low = at.y - extent.y;
    float high = at.y + extent.y;
    float start = clamp(-3 * sigma, low, high);
    float end = clamp(3 * sigma, low, high);
    float step = (end - start) * 0.25;
    float y = start + step * 0.5;
    float value = 0;
    for (int i = 0; i < 4; i++)
    {
        value += shadowRow(at.x, at.y - y, sigma, r, extent) * gaussian(y, sigma) * step;
        y += step;
    }
    return color(p, COLOR_A) * saturate(value);
}

/** Where `px` falls in the primitive's rectangle, 0 to 1 across it. */
float2 across(uint* p, float2 px)
{
    float2 lo = float2(word(p, X0), word(p, Y0));
    float2 hi = float2(word(p, X1), word(p, Y1));
    return (px - lo) / max(hi - lo, 1e-6);
}

/** A texel `s` of an image (straight alpha) times a tint, premultiplied. */
float4 tinted(float4 s, uint* p)
{
    return float4(s.rgb * s.a, s.a) * color(p, COLOR_A);
}

Texture2D<float4> sampled(uint* p)
{
    return textures[NonUniformResourceIndex(push.textures[p[TEXTURE]])];
}

/**
 * A glyph of an MTSDF atlas: the median of the three channels is the multi-channel distance (0.5 on the edge), alpha
 * the true distance, which the outline reads; PIXEL_RANGE is the atlas's range in pixels at this size (times the
 * scale it draws at), as are the weight and the outline width. With FLAG_RASTER it is hinted coverage instead (small
 * text): its quad lies on whole pixels, a texel per pixel, and the texel's alpha times the colour's is the glyph. With
 * FLAG_COLOR it is a colour bitmap (an emoji): the page is RGBA8_SRGB (the view decodes to linear before filtering),
 * straight alpha with its colours bled into transparent texels, drawn as an image is: premultiplied, times colour A
 * (white with the text's opacity).
 */
float4 glyph(uint* p, float2 px, float k)
{
    float2 uv = lerp(unorms(p[UV]), unorms(p[UV + 1]), across(p, px));
    float4 m = sampled(p).SampleLevel(samplers[LINEAR_CLAMP], uv, 0);
    uint flags = (p[HEADER] >> 8) & 0xff;
    if ((flags & FLAG_COLOR) != 0)
    {
        return tinted(m, p);
    }
    if ((flags & FLAG_RASTER) != 0)
    {
        return color(p, COLOR_A) * m.a;
    }
    float range = word(p, PIXEL_RANGE) * k;
    // The atlas encodes distances up to half its range either side of the edge; an edge moved (bold) or an outline
    // reaching further would find only the clamped encoding there and fill the whole quad: both are kept within it.
    float reach = max(range * 0.5 - 1, 0);
    float weight = clamp(word(p, WEIGHT) * k, -reach, reach);
    float distance = range * (median(m.r, m.g, m.b) - 0.5) + weight;
    float fill = saturate(distance + 0.5);
    float4 c = color(p, COLOR_A) * fill;
    float outline = min(word(p, OUTLINE) * k, reach - abs(weight));
    if (outline > 0)
    {
        float outer = saturate(range * (m.a - 0.5) + weight + outline + 0.5);
        c += color(p, COLOR_B) * max(outer - fill, 0);
    }
    return c;
}

float4 image(uint* p, float2 px)
{
    if (p[TEXTURE] == 0)
    {
        return color(p, COLOR_A); // no texture: the tint
    }
    float2 uv = lerp(unorms(p[UV]), unorms(p[UV + 1]), across(p, px));
    return tinted(sampled(p).SampleLevel(samplers[LINEAR_CLAMP], uv, 0), p);
}

/**
 * Along one axis of a nine-slice, `x` pixels into it of `size`: the texel of the source region (`source` texels
 * wide), the first `a` pixels showing its first `sa` texels, the last `b` its last `sb`, the middle stretched.
 */
float slice(float x, float size, float a, float b, float source, float sa, float sb)
{
    if (x < a)
    {
        return x / a * sa;
    }
    if (x > size - b)
    {
        return source - (size - x) / b * sb;
    }
    return sa + (x - a) / max(size - a - b, 1e-4) * (source - sa - sb);
}

float4 nineSlice(uint* p, float2 px)
{
    float2 lo = float2(word(p, X0), word(p, Y0));
    float2 hi = float2(word(p, X1), word(p, Y1));
    float2 uv0 = unorms(p[UV]);
    float2 uv1 = unorms(p[UV + 1]);
    Texture2D<float4> t = sampled(p);
    uint width;
    uint height;
    t.GetDimensions(width, height);
    float2 texels = float2(width, height);
    float2 source = (uv1 - uv0) * texels; // the region's texels
    float2 leftTop = halves(p[INSETS]);
    float2 rightBottom = halves(p[INSETS + 1]);
    uint s = p[SOURCE_INSETS];
    float4 sourceInsets = float4(s & 0xff, (s >> 8) & 0xff, (s >> 16) & 0xff, s >> 24); // left, top, right, bottom
    float2 size = hi - lo;
    float2 at = px - lo;
    float2 texel = float2(
        slice(at.x, size.x, leftTop.x, rightBottom.x, source.x, sourceInsets.x, sourceInsets.z),
        slice(at.y, size.y, leftTop.y, rightBottom.y, source.y, sourceInsets.y, sourceInsets.w));
    float2 uv = uv0 + texel / texels;
    return tinted(t.SampleLevel(samplers[LINEAR_CLAMP], uv, 0), p);
}

/** An anti-aliased segment with butt ends; lines thinner than a pixel (as drawn) are a pixel wide, fainter. */
float4 segment(uint* p, float2 px, float k)
{
    float2 a = float2(word(p, X0), word(p, Y0));
    float2 b = float2(word(p, X1), word(p, Y1));
    float2 d = b - a;
    float len = length(d);
    float2 along = len > 0 ? d / len : float2(1, 0);
    float2 local = px - a;
    float width = word(p, WIDTH) * k; // in pixels
    float extent = max(width, 1) * 0.5;
    float distance = max((abs(dot(local, along) - len * 0.5) - len * 0.5) * k,
        abs(dot(local, float2(-along.y, along.x))) * k - extent);
    return color(p, COLOR_A) * (saturate(0.5 - distance) * min(width, 1));
}

/**
 * The signed edge function of `px` against the edge from `a` to `b`, worked out from the edge's end points in a
 * canonical order (the lesser by x, then y, first) so two triangles sharing the edge compute the very same value;
 * returned with the sign that makes `inside` positive, and `owner` true when this triangle owns pixels exactly on it.
 * Divided by the edge's length: a distance in the primitive's units.
 */
float edgeDistance(float2 a, float2 b, float2 px, float winding, out bool owner)
{
    bool swap = b.x < a.x || (b.x == a.x && b.y < a.y);
    float2 q0 = swap ? b : a;
    float2 q1 = swap ? a : b;
    float2 d = q1 - q0;
    float e = d.x * (px.y - q0.y) - d.y * (px.x - q0.x);
    float side = swap ? -winding : winding;
    owner = side > 0; // of the two triangles sharing the edge, exactly one has side > 0: it owns pixels on it
    return e * side / max(length(d), 1e-12);
}

/** One edge's coverage: its distance's for anti-aliased edges (`k` pixels a unit), half-open and hard for the others. */
float edgeCoverage(float2 a, float2 b, float2 px, float winding, bool smooth, float k)
{
    bool owner;
    float distance = edgeDistance(a, b, px, winding, owner);
    if (smooth)
    {
        return saturate(distance * k + 0.5);
    }
    return distance > 0 || (distance == 0 && owner) ? 1 : 0;
}

/**
 * A filled triangle (corners in words XA to YC): the product over its three edges of each edge's coverage, anti-aliased
 * by the signed distance on the edges its flags name (a shape's outline) and a hard half-open test on the others, so
 * triangles sharing an edge cover each pixel along it exactly once and translucent fills show no seam.
 */
float4 triangle(uint* p, uint header, float2 px, float k)
{
    float2 a = float2(word(p, XA), word(p, YA));
    float2 b = float2(word(p, XB), word(p, YB));
    float2 c = float2(word(p, XC), word(p, YC));
    float2 ab = b - a;
    float2 ac = c - a;
    float area = ab.x * ac.y - ab.y * ac.x;
    if (area == 0)
    {
        return float4(0, 0, 0, 0); // degenerate: no pixels
    }
    float winding = area > 0 ? 1 : -1;
    uint flags = (header >> 8) & 0xff;
    float coverage = edgeCoverage(a, b, px, winding, (flags & EDGE_AB) != 0, k)
        * edgeCoverage(b, c, px, winding, (flags & EDGE_BC) != 0, k)
        * edgeCoverage(c, a, px, winding, (flags & EDGE_CA) != 0, k);
    return color(p, COLOR_A) * coverage;
}

/** How much of `px` (a UI pixel) the primitive's clip lets through: its rounded rectangle where it cuts, anti-aliased. */
float clipped(uint header, float2 px)
{
    uint clip = header >> 16;
    if (clip == 0)
    {
        return 1;
    }
    float radius;
    float4 bounds = clipBounds(clip, radius);
    float2 lo = bounds.xy;
    float2 hi = bounds.zw;
    float2 extent = (hi - lo) * 0.5;
    float r = clamp(radius, 0, min(extent.x, extent.y));
    return saturate(0.5 - roundedBox(px - (lo + hi) * 0.5, extent, r));
}

/** The pixel's centre in UI pixels. */
float2 uiPixel(float4 position)
{
    return position.xy * float2(push.scaleX, push.scaleY) + float2(push.originX, push.originY);
}

/** UI pixel `px` in the coordinates primitive `p` (header `header`, transform `t`) was recorded in. */
float2 ownCoordinates(float2 px, uint header, Transform t)
{
    if (!t.turns)
    {
        return ((px - t.offset) / t.outerScale - clipOffset(header) - t.innerOffset) / t.innerScale;
    }
    float2 q = turnBack(t.outerTurn, (px - t.offset) / t.outerScale) - clipOffset(header);
    return turnBack(t.innerTurn, (q - t.innerOffset) / t.innerScale);
}

// ---------------------------------------------------------------------------------------------------- depth tests

/** The view depth of the scene drawn under image pixel `position` (infinite where nothing is). */
float sceneDepth(float2 position)
{
    uint2 texel = min(uint2(position * float2(push.depthScaleX, push.depthScaleY)),
        uint2(push.depthMaxX, push.depthMaxY));
    float d = textures[push.depth - 1].Load(int3(texel, 0)).r;
    if (push.depthRange > 0)
    {
        return (1 - d) * push.depthRange;
    }
    return d > 0 ? push.depthNear / d : asfloat(0x7f800000u);
}

/**
 * The view depth of depth tested primitive `p` (of kind `kind`) at `px`, in its own coordinates: a box's one depth;
 * along a line its ends' at the pixel's projection onto it, clamped to it; over a triangle its corners' by barycentric
 * weights. Under perspective the reciprocals are interpolated (what is linear on the screen), else the depths.
 */
float primitiveDepth(uint* p, uint kind, float2 px)
{
    float za = asfloat(p[DEPTH] & ~0xffu);
    bool orthographic = push.depthRange > 0;
    if (kind == LINE)
    {
        float zb = word(p, DEPTH_B);
        float2 a = float2(word(p, X0), word(p, Y0));
        float2 d = float2(word(p, X1), word(p, Y1)) - a;
        float t = saturate(dot(px - a, d) / max(dot(d, d), 1e-12));
        return orthographic ? lerp(za, zb, t) : 1 / lerp(1 / za, 1 / zb, t);
    }
    if (kind == TRIANGLE)
    {
        float2 r = halves(p[DEPTH_B]);
        float zb = za * (1 + r.x);
        float zc = za * (1 + r.y);
        float2 a = float2(word(p, XA), word(p, YA));
        float2 ab = float2(word(p, XB), word(p, YB)) - a;
        float2 ac = float2(word(p, XC), word(p, YC)) - a;
        float area = ab.x * ac.y - ab.y * ac.x;
        if (area == 0)
        {
            return za;
        }
        float2 q = px - a;
        float wb = (q.x * ac.y - q.y * ac.x) / area;
        float wc = (ab.x * q.y - ab.y * q.x) / area;
        float wa = 1 - wb - wc;
        return orthographic ? wa * za + wb * zb + wc * zc : 1 / (wa / za + wb / zb + wc / zc);
    }
    return za;
}

/**
 * What primitive `p`'s colour is multiplied by at image pixel `position` (`px` in its own coordinates): its occluded
 * opacity where it is depth tested and the scene is nearer, else 1.
 */
float depthTest(uint* p, uint header, float2 px, float2 position)
{
    if (((header >> 8) & FLAG_DEPTH) == 0 || push.depth == 0)
    {
        return 1;
    }
    float z = primitiveDepth(p, header & 0xff, px);
    return z > sceneDepth(position) * (1 + push.depthBias) ? float(p[DEPTH] & 0xffu) / 255.0 : 1;
}

// ----------------------------------------------------------------------------------------------- fragments, uber path

[shader("fragment")]
float4 fragmentMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    float2 local = ownCoordinates(px, header, t);
    float k = t.scale;
    float4 c;
    switch (header & 0xff)
    {
    case BOX:
        c = box(p, header, local, k);
        break;
    case SHADOW:
        c = shadow(p, local, k);
        break;
    case GLYPH:
        c = glyph(p, local, k);
        break;
    case IMAGE:
        c = image(p, local);
        break;
    case NINE_SLICE:
        c = nineSlice(p, local);
        break;
    case LINE:
        c = segment(p, local, k);
        break;
    case TRIANGLE:
        c = triangle(p, header, local, k);
        break;
    default:
        c = float4(0, 0, 0, 0);
        break;
    }
    return c * (t.opacity * clipped(header, px) * depthTest(p, header, local, in.position.xy));
}

// ------------------------------------------------------------------------------------------ fragments, per-kind path

[shader("fragment")]
float4 boxMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    float2 local = ownCoordinates(px, header, t);
    return box(p, header, local, t.scale) * (t.opacity * clipped(header, px) * depthTest(p, header, local,
        in.position.xy));
}

[shader("fragment")]
float4 shadowMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    return shadow(p, ownCoordinates(px, header, t), t.scale) * (t.opacity * clipped(header, px));
}

[shader("fragment")]
float4 glyphMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    return glyph(p, ownCoordinates(px, header, t), t.scale) * (t.opacity * clipped(header, px));
}

[shader("fragment")]
float4 imageMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    return image(p, ownCoordinates(px, header, t)) * (t.opacity * clipped(header, px));
}

[shader("fragment")]
float4 nineSliceMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    return nineSlice(p, ownCoordinates(px, header, t)) * (t.opacity * clipped(header, px));
}

[shader("fragment")]
float4 lineMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    float2 local = ownCoordinates(px, header, t);
    return segment(p, local, t.scale) * (t.opacity * clipped(header, px) * depthTest(p, header, local,
        in.position.xy));
}

[shader("fragment")]
float4 triangleMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    float2 local = ownCoordinates(px, header, t);
    return triangle(p, header, local, t.scale) * (t.opacity * clipped(header, px) * depthTest(p, header, local,
        in.position.xy));
}

// ------------------------------------------------------------------------------------------------- a folded backdrop
// A box covering the whole image at its bottom is cleared to (GpuUiRenderer.foldBackdrop); if it has rounded corners or
// a border, its four edges, as deep as they reach, are drawn by fragmentMain over the clear without blending (what the
// box covers there is transparent): the box's own pixels, its middle the clear's.

[shader("vertex")]
VertexOut edgesVertexMain(uint vertex : SV_VulkanVertexID)
{
    uint index = push.first;
    uint* p = push.primitives + index * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 offset = clipOffset(header);
    float2 a = place(t, float2(word(p, X0), word(p, Y0)), offset);
    float2 b = place(t, float2(word(p, X1), word(p, Y1)), offset);
    float2 lo = min(a, b) - 0.5;
    float2 hi = max(a, b) + 0.5;
    float2 top = halves(p[RADII]);
    float2 bottom = halves(p[RADII + 1]);
    float reach = max(max(max(top.x, top.y), max(bottom.x, bottom.y)), word(p, WIDTH)) * t.scale + 1.5;
    uint side = vertex / 6;
    uint corner = CORNERS[vertex % 6];
    float2 s0;
    float2 s1;
    if (side == 0)
    {
        s0 = lo;
        s1 = float2(hi.x, lo.y + reach);
    }
    else if (side == 1)
    {
        s0 = float2(lo.x, hi.y - reach);
        s1 = hi;
    }
    else
    {
        float x = side == 2 ? lo.x : hi.x - reach;
        s0 = float2(x, lo.y + reach);
        s1 = float2(x + reach, max(lo.y + reach, hi.y - reach)); // nothing between the top and bottom edges: no area
    }
    float2 at = float2((corner & 1) != 0 ? s1.x : s0.x, (corner & 2) != 0 ? s1.y : s0.y);
    VertexOut out;
    out.primitive = index;
    out.position = float4((at.x - push.originX) * push.toClipX - 1, (at.y - push.originY) * push.toClipY - 1, 0, 1);
    return out;
}

// ------------------------------------------------------------------------------------------------ kinds over vertices
// PATH and COLUMNS draw points of the layer's vertex table (push.vertices), one draw each: SV_VertexID / 6 is the
// segment or column, the primitive is push.first. Their geometry is worked out in UI pixels (each point moved by the
// primitive's clip offset and transform entry), so widths and anti-aliasing are in pixels.
//
// A path: each segment's quad covers its body (butt ends), its caps where the path starts or ends, and the join at its
// end (the wedge on the outer side of the bend: miter, bevel past the miter limit, or round). Segments next to each
// other overlap at their joins; each pixel is drawn by the segment covering it most among its neighbours (the others
// discard it), with that coverage, so a path is the union of its segments: no seam, no doubled translucency at a join.
// Dashes run along the length stored with each point.
//
// Columns (a min-max envelope): filled, a quad a pixel column, the column's span from the baseline to its farthest
// sample, anti-aliased at its ends; stroked, a quad a piece: each column's vertical stroke over its span (grown by half
// the width), and from the column before a connector (its last sample to this one's first, or stairs: level, then up
// or down) where their spans do not touch, each drawn as a LINE would be, blending where they overlap as lines do.

struct ItemOut
{
    float4 position : SV_Position;
    nointerpolation uint primitive : PRIMITIVE;
    nointerpolation uint item : ITEM; // the segment or column
};

/** Neighbouring segments either side a path's pixel may be shared with. */
static const int PATH_REACH = 2;

/** Vertex `j` of primitive `p`'s vertices. */
float4 vertexAt(uint* p, int j)
{
    return push.vertices[p[FIRST_VERTEX] + uint(j)]; // VERTEX_FLOATS: one float4
}

/** Not NaN, not infinite (NaN fails every comparison). */
bool present(float v)
{
    return abs(v) < 1e30;
}

ItemOut noItem(uint index, uint item)
{
    ItemOut out;
    out.position = float4(-2, -2, 0, 1); // no area, no pixels
    out.primitive = index;
    out.item = item;
    return out;
}

ItemOut itemAt(float2 at, uint index, uint item)
{
    ItemOut out;
    out.position = float4((at.x - push.originX) * push.toClipX - 1, (at.y - push.originY) * push.toClipY - 1, 0, 1);
    out.primitive = index;
    out.item = item;
    return out;
}

/** A segment from `a` to `b` `width` pixels wide with butt ends, as the LINE kind draws it (at least a pixel wide). */
float lineCover(float2 a, float2 b, float width, float2 px)
{
    float2 d = b - a;
    float len = length(d);
    if (!(len > 0))
    {
        return 0;
    }
    d /= len;
    float2 q = px - a;
    float along = dot(q, d);
    float across = abs(dot(q, float2(-d.y, d.x)));
    return saturate(max(width, 1) * 0.5 + 0.5 - across) * saturate(min(along, len - along) + 0.5) * min(width, 1);
}

// ------------------------------------------------------------------------------------------------------------ paths

struct Path
{
    uint* p;
    Transform t;
    float2 offset;
    int count;
    float hw;      // half the width, at least half a pixel
    float fade;    // lines thinner than a pixel are a pixel wide, fainter
    uint join;
    uint cap;
    float limit;
    float4 dashes; // dash, gap, dash, gap (pixels)
    float phase;
};

Path pathOf(uint* p, uint header)
{
    Path q;
    q.p = p;
    q.t = transformAt(p[TRANSFORM]);
    q.offset = clipOffset(header);
    q.count = int(p[VERTEX_COUNT]);
    float w = word(p, WIDTH) * q.t.scale;
    q.hw = max(w, 1) * 0.5;
    q.fade = min(w, 1);
    q.join = p[STROKE] & 3;
    q.cap = p[STROKE] & 12;
    q.limit = word(p, MITER_LIMIT);
    q.dashes = float4(halves(p[DASHES]), halves(p[DASHES + 1])) * q.t.scale;
    q.phase = word(p, DASH_PHASE) * q.t.scale;
    return q;
}

/** Point `j` of the path in UI pixels, with its length along the path; false if there is none or it is a break. */
bool pathPoint(Path q, int j, out float2 at, out float s)
{
    at = float2(0, 0);
    s = 0;
    if (j < 0 || j >= q.count)
    {
        return false;
    }
    float4 v = vertexAt(q.p, j);
    at = place(q.t, v.xy, q.offset);
    s = v.z * q.t.scale;
    return present(v.x) && present(v.y);
}

/** Segment `j` (points j, j + 1): false if either is missing or they coincide. */
bool pathSegment(Path q, int j, out float2 a, out float2 b, out float sa, out float sb)
{
    bool first = pathPoint(q, j, a, sa);
    bool second = pathPoint(q, j + 1, b, sb);
    return first && second && any(a != b);
}

bool hasSegment(Path q, int j)
{
    float2 a;
    float2 b;
    float sa;
    float sb;
    return pathSegment(q, j, a, b, sa, sb);
}

/** How much of the dash pattern covers the point `s` pixels along the path (1 when solid). */
float dashCover(Path q, float s)
{
    float4 d = q.dashes;
    float total = d.x + d.y + d.z + d.w;
    if (!(total > 0) || d.y + d.w <= 0)
    {
        return 1;
    }
    float x = s + q.phase;
    x -= floor(x / total) * total;
    float best = -1e30;
    for (int k = -1; k <= 1; k++)
    {
        float o = k * total;
        best = max(best, min(x - o, o + d.x - x));
        float second = o + d.x + d.y;
        best = max(best, min(x - second, second + d.z - x));
    }
    return saturate(best + 0.5);
}

/** The join at `b`, the end of the segment along `d`, on the outer side of the bend towards `c`. */
float joinCover(Path q, float2 b, float2 d, float2 c, float2 px)
{
    float2 e = normalize(c - b);
    float turn = d.x * e.y - d.y * e.x;
    float2 r = px - b;
    if (abs(turn) < 1e-6 || dot(r, d) < 0 || dot(r, e) > 0)
    {
        return 0; // straight on, or outside the wedge between the two segments' ends
    }
    if (q.join == JOIN_ROUND)
    {
        return saturate(q.hw + 0.5 - length(r));
    }
    float side = turn > 0 ? -1 : 1; // the outer side: away from the turn
    float2 o1 = float2(-d.y, d.x) * side;
    float2 o2 = float2(-e.y, e.x) * side;
    float cover = min(saturate(q.hw + 0.5 - dot(r, o1)), saturate(q.hw + 0.5 - dot(r, o2)));
    float cosHalf = sqrt(max(0, (1 + dot(d, e)) * 0.5));
    if (q.join == JOIN_BEVEL || cosHalf * q.limit < 1) // a miter longer than the limit: beveled
    {
        float2 m = normalize(o1 + o2);
        cover = min(cover, saturate(q.hw * dot(m, o1) + 0.5 - dot(r, m)));
    }
    return cover;
}

/** Coverage of `px` by segment `j`: its body, its caps, and the join at its end. */
float segmentCover(Path q, int j, float2 px)
{
    float2 a;
    float2 b;
    float sa;
    float sb;
    if (!pathSegment(q, j, a, b, sa, sb))
    {
        return 0;
    }
    float len = length(b - a);
    float2 d = (b - a) / len;
    float2 r = px - a;
    float t = dot(r, d);
    float u = dot(r, float2(-d.y, d.x));
    bool starts = !hasSegment(q, j - 1); // a cap at a, else a join the segment before owns
    bool ends = !hasSegment(q, j + 1);
    float e0 = starts && q.cap == CAP_SQUARE ? q.hw : 0;
    float e1 = ends && q.cap == CAP_SQUARE ? q.hw : 0;
    float from = saturate(t + e0 + 0.5);
    float to = saturate(len + e1 - t + 0.5);
    // Where a segment meets the next, the two ends are not edges: each body is whole up to its end, and past it only
    // where no other body is either (the outer side of the bend), so a join or a notch shows, anti-aliased there.
    if (!starts)
    {
        float2 before;
        float sbefore;
        pathPoint(q, j - 1, before, sbefore);
        from = t >= 0 ? 1 : dot(r, a - before) > 0 ? from : 0;
    }
    if (!ends)
    {
        float2 after;
        float safter;
        pathPoint(q, j + 2, after, safter);
        to = t <= len ? 1 : dot(px - b, after - b) < 0 ? to : 0;
    }
    float body = saturate(q.hw + 0.5 - abs(u)) * min(from, to);
    if (starts && q.cap == CAP_ROUND && t < 0)
    {
        body = saturate(q.hw + 0.5 - length(r));
    }
    if (ends && q.cap == CAP_ROUND && t > len)
    {
        body = saturate(q.hw + 0.5 - length(px - b));
    }
    float cover = body * dashCover(q, sa + clamp(t, 0, len));
    if (!ends && q.join != JOIN_NONE)
    {
        float2 c;
        float sc;
        pathPoint(q, j + 2, c, sc);
        cover = max(cover, joinCover(q, b, d, c, px) * dashCover(q, sb));
    }
    return cover * q.fade;
}

[shader("vertex")]
ItemOut pathVertexMain(uint vertex : SV_VulkanVertexID)
{
    int j = int(vertex / 6);
    uint corner = CORNERS[vertex % 6];
    uint index = push.first;
    uint* p = push.primitives + index * WORDS;
    Path q = pathOf(p, p[HEADER]);
    float2 a;
    float2 b;
    float sa;
    float sb;
    if (!pathSegment(q, j, a, b, sa, sb))
    {
        return noItem(index, uint(j));
    }
    float2 d = normalize(b - a);
    float2 n = float2(-d.y, d.x);
    bool starts = !hasSegment(q, j - 1);
    bool ends = !hasSegment(q, j + 1);
    float cap = q.cap != CAP_BUTT ? q.hw : 0;
    float reach = q.hw + 1; // the body's half width and its anti-aliased edge
    float join = 1;
    if (!ends && q.join != JOIN_NONE)
    {
        join = q.hw + 1;
        if (q.join == JOIN_MITER)
        {
            float2 c;
            float sc;
            pathPoint(q, j + 2, c, sc);
            float cosHalf = sqrt(max(0, (1 + dot(d, normalize(c - b))) * 0.5));
            if (cosHalf * q.limit >= 1)
            {
                join = q.hw / max(cosHalf, 1e-3) + 1; // the miter's tip
            }
        }
    }
    float ext0 = (starts ? cap : 0) + 1;
    float ext1 = ends ? cap + 1 : join;
    float across = max(reach, join);
    float2 at = ((corner & 1) != 0 ? b + d * ext1 : a - d * ext0) + n * ((corner & 2) != 0 ? across : -across);
    return itemAt(at, index, uint(j));
}

[shader("fragment")]
float4 pathMain(ItemOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Path q = pathOf(p, header);
    float2 px = uiPixel(in.position);
    int j = int(in.item);
    float cover = segmentCover(q, j, px);
    if (!(cover > 0))
    {
        discard;
    }
    for (int k = j - PATH_REACH; k <= j + PATH_REACH; k++)
    {
        if (k == j)
        {
            continue;
        }
        float other = segmentCover(q, k, px);
        if (other > cover || other == cover && k < j)
        {
            discard; // a neighbour covers it more: it draws it
        }
    }
    return color(p, COLOR_A) * (cover * q.t.opacity * clipped(header, px));
}

// ---------------------------------------------------------------------------------------------------------- columns

struct Columns
{
    uint* p;
    Transform t;
    float2 offset;
    int count;
    float x;     // the first column's centre, UI pixels
    float step;  // pixels to the next column (negative: leftwards)
    float width; // the stroke's width, pixels (0: filled)
    bool fill;
    bool steps;
    float base;
};

Columns columnsOf(uint* p, uint header)
{
    Columns c;
    c.p = p;
    c.t = transformAt(p[TRANSFORM]);
    c.offset = clipOffset(header);
    c.count = int(p[VERTEX_COUNT]);
    c.x = placeX(c.t, word(p, COLUMN_X), c.offset.x);
    c.step = word(p, COLUMN_STEP) * c.t.scale;
    c.width = word(p, WIDTH) * c.t.scale;
    c.fill = (p[STROKE] & COLUMNS_FILL) != 0;
    c.steps = (p[STROKE] & COLUMNS_STEPS) != 0;
    c.base = placeY(c.t, word(p, BASELINE), c.offset.y);
    return c;
}

/** Column `k`'s first, last, lowest and highest sample in UI pixels; x not present when there is none. */
float4 columnAt(Columns c, int k)
{
    if (k < 0 || k >= c.count)
    {
        return float4(asfloat(0x7fc00000u), 0, 0, 0);
    }
    float4 v = vertexAt(c.p, k);
    return float4(placeY(c.t, v.x, c.offset.y), placeY(c.t, v.y, c.offset.y), placeY(c.t, v.z, c.offset.y),
        placeY(c.t, v.w, c.offset.y));
}

/** Columns of a fill a quad covers (GpuUiRenderer.FILL_GROUP): quads a pixel wide rasterize slowly. */
static const int FILL_GROUP = 4;

struct PieceOut
{
    float4 position : SV_Position;
    nointerpolation uint primitive : PRIMITIVE;
    nointerpolation float4 piece : PIECE; // a stroked piece's ends a (xy) and b (zw), UI pixels; a column's span
};

PieceOut noPiece(uint index)
{
    PieceOut out;
    out.position = float4(-2, -2, 0, 1); // no area, no pixels
    out.primitive = index;
    out.piece = float4(0, 0, 0, 0);
    return out;
}

PieceOut pieceAt(float2 at, uint index, float4 piece)
{
    PieceOut out;
    out.position = float4((at.x - push.originX) * push.toClipX - 1, (at.y - push.originY) * push.toClipY - 1, 0, 1);
    out.primitive = index;
    out.piece = piece;
    return out;
}

/**
 * Stroked columns: three quads a column, each a piece drawn as a LINE would draw it (butt ends, at least a pixel wide):
 * its vertical stroke over its span grown by half the width (when it spans half a pixel), then the connector from the
 * column before (its last sample to this one's first) where their spans do not touch, as one segment or as stairs
 * (level, then up or down: two). Pieces overlap and blend as segments did. Filled columns: a quad a column, from the
 * baseline to the farthest sample.
 */
[shader("vertex")]
PieceOut columnsVertexMain(uint vertex : SV_VulkanVertexID)
{
    uint index = push.first;
    uint* p = push.primitives + index * WORDS;
    uint header = p[HEADER];
    Columns c = columnsOf(p, header);
    uint corner = CORNERS[vertex % 6];
    if (c.fill)
    {
        int k0 = int(vertex / 6) * FILL_GROUP;
        int k1 = min(k0 + FILL_GROUP, c.count) - 1;
        float top = c.base;
        float bottom = c.base;
        bool found = false;
        for (int k = k0; k <= k1; k++)
        {
            float4 v = columnAt(c, k);
            if (present(v.x))
            {
                top = min(top, v.z);
                bottom = max(bottom, v.w);
                found = true;
            }
        }
        if (!found)
        {
            return noPiece(index);
        }
        float half = abs(c.step) * 0.5;
        float a = c.x + c.step * k0;
        float b = c.x + c.step * k1;
        float2 lo = float2(min(a, b) - half, top - 1);
        float2 hi = float2(max(a, b) + half, bottom + 1);
        uint clip = header >> 16;
        if (clip != 0)
        {
            float radius;
            float4 bounds = clipBounds(clip, radius);
            lo = max(lo, bounds.xy);
            hi = min(hi, bounds.zw);
            if (any(lo >= hi))
            {
                return noPiece(index);
            }
        }
        return pieceAt(float2((corner & 1) != 0 ? hi.x : lo.x, (corner & 2) != 0 ? hi.y : lo.y), index,
            float4(0, 0, 0, 0));
    }
    int k = int(vertex / 18);
    int piece = int(vertex % 18) / 6;
    float4 v = columnAt(c, k);
    if (!present(v.x))
    {
        return noPiece(index);
    }
    float cx = c.x + c.step * k;
    bool tall = v.w - v.z >= 0.5;
    float2 a;
    float2 b;
    if (piece == 0)
    {
        if (!tall)
        {
            return noPiece(index);
        }
        a = float2(cx, v.z - c.width * 0.5);
        b = float2(cx, v.w + c.width * 0.5);
    }
    else
    {
        float4 before = columnAt(c, k - 1);
        bool touching = tall && before.w - before.z >= 0.5 && before.z <= v.w + c.width && v.z <= before.w + c.width
            && abs(c.step) <= 1.5;
        if (!present(before.x) || touching || piece == 2 && !c.steps)
        {
            return noPiece(index);
        }
        float px0 = cx - c.step;
        if (!c.steps)
        {
            a = float2(px0, before.y);
            b = float2(cx, v.x);
        }
        else if (piece == 1)
        {
            a = float2(px0, before.y);
            b = float2(cx, before.y);
        }
        else
        {
            a = float2(cx, before.y);
            b = float2(cx, v.x);
        }
    }
    float2 d = b - a;
    float len = length(d);
    if (!(len > 0))
    {
        return noPiece(index);
    }
    float2 along = d / len;
    float2 normal = float2(-along.y, along.x);
    float extent = max(c.width, 1) * 0.5 + 0.5; // coverage is 0 from half a pixel past the edge on
    float2 at = ((corner & 1) != 0 ? b + along * 0.5 : a - along * 0.5)
        + normal * ((corner & 2) != 0 ? extent : -extent);
    return pieceAt(at, index, float4(a, b));
}

[shader("fragment")]
float4 columnsMain(PieceOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    float cover;
    if ((p[STROKE] & COLUMNS_FILL) != 0)
    {
        // the fill of the column the pixel lies in, from the baseline to its farthest sample, anti-aliased there
        Columns c = columnsOf(p, header);
        float4 v = columnAt(c, int(round((px.x - c.x) / c.step)));
        if (!present(v.x))
        {
            discard;
        }
        cover = saturate(px.y - min(c.base, v.z) + 0.5) * saturate(max(c.base, v.w) - px.y + 0.5);
        if (!(cover > 0))
        {
            discard; // outside its column's fill, within the quad of its neighbours'
        }
    }
    else
    {
        cover = lineCover(in.piece.xy, in.piece.zw, word(p, WIDTH) * t.scale, px);
    }
    return color(p, COLOR_A) * (cover * t.opacity * clipped(header, px));
}

// ------------------------------------------------------------------------------------------------------ world panels
// A world panel's UI drawn directly in the 3D view (engine-ui's WorldPanelRenderer, design UI12): the primitives
// placed as for the screen (in the panel's pixels: origin 0, toClip 2 / its size), then carried into the view by the
// push's world matrix (panel pixel to clip space, made on the CPU relative to the camera); depth tested against the
// scene (its depth tested primitives are not: no depth is bound). Coverage is worked out in the panel's pixels, the pixel's footprint there (fwidth) turning distances into
// screen pixels, so text (MTSDF) stays sharp at any angle and distance.

struct WorldOut
{
    float4 position : SV_Position;
    nointerpolation uint primitive : PRIMITIVE;
    float2 pixel : PIXEL; // the panel pixel
};

[shader("vertex")]
WorldOut worldVertexMain(uint vertex : SV_VulkanVertexID, uint view : SV_ViewID)
{
    VertexOut flat = vertexMain(vertex);
    WorldOut out;
    out.primitive = flat.primitive;
    if (flat.position.x == -2 && flat.position.y == -2)
    {
        out.position = float4(0, 0, -1, 1); // no area: behind the near plane
        out.pixel = float2(0, 0);
        return out;
    }
    float2 at = (flat.position.xy + 1) / float2(push.toClipX, push.toClipY);
    float* m = push.world + 16 * view; // a matrix per view: a multiview pass draws each eye through its own
    out.pixel = at;
    out.position = float4(m[0] * at.x + m[4] * at.y + m[12], m[1] * at.x + m[5] * at.y + m[13],
        m[2] * at.x + m[6] * at.y + m[14], m[3] * at.x + m[7] * at.y + m[15]);
    return out;
}

[shader("fragment")]
float4 worldFragmentMain(WorldOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = in.pixel;
    float2 local = ownCoordinates(px, header, t);
    float2 footprint = fwidth(px); // panel pixels per screen pixel
    float k = t.scale / max(max(footprint.x, footprint.y), 1e-4);
    float4 c;
    switch (header & 0xff)
    {
    case BOX:
        c = box(p, header, local, k);
        break;
    case SHADOW:
        c = shadow(p, local, k);
        break;
    case GLYPH:
        c = glyph(p, local, k);
        break;
    case IMAGE:
        c = image(p, local);
        break;
    case NINE_SLICE:
        c = nineSlice(p, local);
        break;
    case LINE:
        c = segment(p, local, k);
        break;
    case TRIANGLE:
        c = triangle(p, header, local, k);
        break;
    default:
        c = float4(0, 0, 0, 0);
        break;
    }
    return c * (t.opacity * clipped(header, px));
}

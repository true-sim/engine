// The UI's primitives (ui-core's draw list, laid out as ui/primitive.slang says) drawn into the UI image: premultiplied
// alpha, linear, 1.0 paper white. Vertex pulling: six vertices per primitive, SV_VertexID giving the primitive and
// the corner, no vertex buffers. Each primitive's quad covers what it may touch (a box's anti-aliased edge, a shadow's
// blur, a line's width), cut to its clip rectangle's bounds; the fragment shader works out coverage from the pixel's
// position by the primitive's kind (a signed distance for boxes, lines and glyphs), clipped by the clip's rounded
// rectangle. EMPTY primitives (padding of an element's range) are degenerate: no pixels.
//
// Where a primitive draws: its coordinates plus its clip's offset (a scrolled region records its content once,
// unscrolled, and scrolling moves the offset), then its transform table entry's scale and offset (an element's
// animated scale or drawn offset: the animation rewrites the entry, not the primitives). The vertices are placed so;
// the kinds' coverage is worked out at the pixel mapped back into the primitive's own coordinates, distances scaled to
// pixels by the entry's scale, and the colour times the entry's opacity. A clip's rectangle is drawn by its own
// entry (its owner's: a scaled or moved subtree is cut where it draws) and tested at the pixel itself.
//
// One draw per layer runs fragmentMain, which branches on the kind (the "uber" path); the per-kind path draws runs of
// one kind with that kind's own fragment entry point (boxMain, glyphMain, ...), in list order.
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
    float* world;       // a world panel drawn in the 3D view: 16 floats, its pixels to clip space (column-major)
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

/** A transform table entry: points map to scale * p + offset, colours times opacity. */
struct Transform
{
    float scale;
    float2 offset;
    float opacity;
};

/** Entry `index` of the transform table; entry 0, the identity of nearly every primitive, without a memory read. */
Transform transformAt(uint index)
{
    Transform x;
    if (index == 0)
    {
        x.scale = 1;
        x.offset = float2(0, 0);
        x.opacity = 1;
        return x;
    }
    float* t = push.transforms + index * TRANSFORM_FLOATS;
    x.scale = t[0];
    x.offset = float2(t[1], t[2]);
    x.opacity = t[3];
    return x;
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
    if (kind == EMPTY || kind >= KINDS)
    {
        out.position = float4(-2, -2, 0, 1); // every corner at one point: no area, no pixels
        return out;
    }
    Transform t = transformAt(p[TRANSFORM]);
    float2 offset = clipOffset(header);
    float2 a = (float2(word(p, X0), word(p, Y0)) + offset) * t.scale + t.offset;
    float2 b = (float2(word(p, X1), word(p, Y1)) + offset) * t.scale + t.offset;
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
    return (px - t.offset) / t.scale - clipOffset(header);
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
    return c * (t.opacity * clipped(header, px));
}

// ------------------------------------------------------------------------------------------ fragments, per-kind path

[shader("fragment")]
float4 boxMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    return box(p, header, ownCoordinates(px, header, t), t.scale) * (t.opacity * clipped(header, px));
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
    return segment(p, ownCoordinates(px, header, t), t.scale) * (t.opacity * clipped(header, px));
}

[shader("fragment")]
float4 triangleMain(VertexOut in) : SV_Target
{
    uint* p = push.primitives + in.primitive * WORDS;
    uint header = p[HEADER];
    Transform t = transformAt(p[TRANSFORM]);
    float2 px = uiPixel(in.position);
    return triangle(p, header, ownCoordinates(px, header, t), t.scale) * (t.opacity * clipped(header, px));
}

// ------------------------------------------------------------------------------------------------------ world panels
// A world panel's UI drawn directly in the 3D view (engine-ui's WorldPanelRenderer, design UI12): the primitives
// placed as for the screen (in the panel's pixels: origin 0, toClip 2 / its size), then carried into the view by the
// push's world matrix (panel pixel to clip space, made on the CPU relative to the camera); depth tested against the
// scene. Coverage is worked out in the panel's pixels, the pixel's footprint there (fwidth) turning distances into
// screen pixels, so text (MTSDF) stays sharp at any angle and distance.

struct WorldOut
{
    float4 position : SV_Position;
    nointerpolation uint primitive : PRIMITIVE;
    float2 pixel : PIXEL; // the panel pixel
};

[shader("vertex")]
WorldOut worldVertexMain(uint vertex : SV_VulkanVertexID)
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
    float* m = push.world;
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

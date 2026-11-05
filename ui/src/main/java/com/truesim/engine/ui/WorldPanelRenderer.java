package com.truesim.engine.ui;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutFloat;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.MemoryUtil.memPutLong;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_RENDERING_INFO;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Load;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Store;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.ui.layout.UiPush;
import com.truesim.engine.vulkan.Commands;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.Images;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.ui.controls.Ui;
import com.truesim.ui.scene.WorldPanel;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

/**
 * Draws {@link WorldPanel}s (design 6.4, UI12) in a 3D view, each in one of two ways chosen per panel:
 *
 * <ul>
 *   <li><b>{@link Mode#TEXTURE}</b>: the panel's UI is rendered into an image of its own (premultiplied, linear,
 *       16-bit floats) with a bindless sampled slot ({@link #textureSlot}), which a scene material samples like any
 *       texture (a metallic-roughness material's base colour or emissive texture), so the panel is lit, fogged,
 *       reflected and sorted as the scene's surfaces are. It is rendered again only in frames where its draw lists or
 *       textures changed, at the frame's start (before every pass of the frame, so the frame's shading samples this
 *       frame's image: no frame of delay), outside the graph's passes as texture uploads are.</li>
 *   <li><b>{@link Mode#DIRECT}</b>: the panel's primitives are drawn in the view after its opaque scene
 *       ({@link Anchor#TRANSPARENT}), placed by the panel's {@link WorldPanel#transform} through the view's camera (a
 *       matrix from the panel's pixels to clip space, made in doubles relative to the camera), depth tested against the
 *       scene's depth, blended over the scene colour; coverage is worked out in the panel's pixels (MTSDF text sharp at
 *       any angle). No texture, no resampling, but unlit: the scene's light does not reach it.</li>
 * </ul>
 *
 * <pre>{@code
 * WorldPanelRenderer panels = new WorldPanelRenderer(graph, camera);
 * mainView.add(panels);                           // the direct panels' pass
 * panels.add(screen);                             // a monitor in the game: lit like the scene (TEXTURE)
 * int material = materials.add(new Material(MR.withBaseColorTexture(panels.textureSlot(screen))...));
 * panels.add(label, Mode.DIRECT);                 // a floating label
 * // each frame, after the panels' UIs updated (UiScene.update), before the frame records:
 * panels.submit();
 * }</pre>
 *
 * <p>VR: made with a camera per eye ({@link #WorldPanelRenderer(FrameGraph, Camera...)}), in a view whose scene colour
 * and depth have a layer per eye, the direct panels draw into every eye in one pass (multiview: the frame graph opens
 * the pass over all the layers, each eye's vertices placed by its own matrix, picked by {@code SV_ViewID}), each eye
 * depth tested against its own layer of the scene's depth. With one camera the view's images must have one layer.
 * Texture panels need nothing more for stereo: the scene samples their image from every eye. Per frame it allocates
 * nothing.
 */
public final class WorldPanelRenderer implements Technique, FrameSlots.FrameStart, AutoCloseable {

    /** The id of a view's world panel renderer. */
    public static final Id<WorldPanelRenderer> ID = Id.of("truesim.ui.worldPanels", WorldPanelRenderer.class);

    /** How a panel is drawn. */
    public enum Mode {
        /** Into a texture a scene material samples: lit and fogged like the scene, re-rendered when it changes. */
        TEXTURE,
        /** Its primitives in the 3D view, depth tested, unlit. */
        DIRECT
    }

    private static final int BUFFER_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;

    /** One panel: its uploads and how it is drawn. */
    private static final class Entry {
        final WorldPanel panel;
        final Mode mode;
        final GpuUiRenderer uploads;
        final FrameBuffer matrix; // DIRECT: panel pixels to clip space, 16 floats
        GpuImage image;           // TEXTURE: what it renders into
        int slot = -1;
        int width;
        int height;
        boolean rendered;
        boolean dirty = true;
        long renders;

        Entry(WorldPanel panel, Mode mode, GpuUiRenderer uploads, FrameBuffer matrix) {
            this.panel = panel;
            this.mode = mode;
            this.uploads = uploads;
            this.matrix = matrix;
        }
    }

    private final FrameGraph graph;
    private final VulkanDevice device;
    private final Camera[] cameras; // one per view (eye) of the pass
    private final List<Entry> entries = new ArrayList<>();
    private final UiPush rawPush = new UiPush();
    private final double[] pose = new double[8];
    private final double[] m = new double[16];
    private final double[] right = new double[3];
    private final double[] down = new double[3];
    private final double[] corner = new double[3];
    private final com.truesim.engine.vulkan.Retiring retiring;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment matrixBytes;
    private Pipeline world;
    private int worldColor;
    private int worldDepth;
    private int worldViews;
    private boolean closed;

    /** A renderer of panels in a view of {@code graph} seen through {@code viewCamera}. */
    @AllowAllocation("made once")
    public WorldPanelRenderer(FrameGraph graph, Camera viewCamera) {
        this(graph, new Camera[] {Objects.requireNonNull(viewCamera, "viewCamera")});
    }

    /**
     * A renderer of panels in a view of {@code graph} with a layer per camera of {@code eyes} (a headset's eyes, left
     * first, in a view whose scene colour and depth have as many layers): direct panels draw into every eye in one
     * multiview pass, each eye through its camera.
     */
    @AllowAllocation("made once")
    public WorldPanelRenderer(FrameGraph graph, Camera... eyes) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.device = graph.device();
        if (eyes.length < 1 || eyes.length > com.truesim.engine.graph.GraphBuilder.MAX_VIEWS) {
            throw new IllegalArgumentException("A view has 1 to " + com.truesim.engine.graph.GraphBuilder.MAX_VIEWS
                + " eyes: " + eyes.length);
        }
        this.cameras = eyes.clone();
        for (Camera eye : cameras) {
            Objects.requireNonNull(eye, "eye");
        }
        this.matrixBytes = arena.allocate(64L * cameras.length, 16);
        this.retiring = new com.truesim.engine.vulkan.Retiring(graph.frameSlots());
        graph.frameSlots().register(this);
    }

    /**
     * Draws {@code panel} into a texture ({@link Mode#TEXTURE}, the default: lit and fogged like the scene by the
     * material that samples it); {@link #add(WorldPanel, Mode)} chooses {@link Mode#DIRECT} instead.
     */
    @AllowAllocation("panels are added when made")
    public WorldPanelRenderer add(WorldPanel panel) {
        return add(panel, Mode.TEXTURE);
    }

    /** Draws {@code panel} by {@code mode}; returns this. */
    @AllowAllocation("panels are added when a scene is built")
    public WorldPanelRenderer add(WorldPanel panel, Mode mode) {
        Objects.requireNonNull(panel, "panel");
        Objects.requireNonNull(mode, "mode");
        if (find(panel) != null) {
            throw new IllegalArgumentException("The panel is drawn already");
        }
        FrameBuffer matrix = mode == Mode.DIRECT ? FrameBuffer.create(device, 64L * cameras.length, BUFFER_USAGE,
            graph.frameSlots())
            : null;
        Entry e = new Entry(panel, mode, new GpuUiRenderer(graph), matrix);
        if (mode == Mode.TEXTURE) {
            e.width = Math.max(1, (int) Math.ceil(panel.width()));
            e.height = Math.max(1, (int) Math.ceil(panel.height()));
            e.image = new GpuImage(device, e.width, e.height, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            e.slot = device.bindless().addSampled(e.image.view());
        }
        entries.add(e);
        // after the panel's own uploads (its atlas pages) at each frame's start: drawn with them in place
        graph.frameSlots().unregister(this);
        graph.frameSlots().register(this);
        return this;
    }

    /** Stops drawing {@code panel}; its buffers and image are freed once no frame in flight uses them. */
    @AllowAllocation("panels are removed rarely")
    public void remove(WorldPanel panel) {
        Entry e = find(panel);
        if (e == null) {
            return;
        }
        entries.remove(e);
        retire(e);
    }

    @AllowAllocation("closing")
    private void retire(Entry e) {
        device.waitIdle(); // rare: a panel removed; its image and buffers may be in flight
        e.uploads.close();
        if (e.matrix != null) {
            e.matrix.close();
        }
        if (e.image != null) {
            device.bindless().removeSampled(e.slot);
            e.image.close();
        }
    }

    private Entry find(WorldPanel panel) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).panel == panel) {
                return entries.get(i);
            }
        }
        return null;
    }

    /**
     * The bindless sampled slot of a {@link Mode#TEXTURE} panel's image, for a material's texture field (sampler 0):
     * the same for as long as the panel is drawn.
     */
    public int textureSlot(WorldPanel panel) {
        Entry e = find(panel);
        if (e == null || e.mode != Mode.TEXTURE) {
            throw new IllegalArgumentException("Not a texture panel of this renderer");
        }
        return e.slot;
    }

    /** How often a texture panel was rendered: only in frames its UI changed. */
    public long renders(WorldPanel panel) {
        Entry e = find(panel);
        return e == null ? 0 : e.renders;
    }

    /** How many panels it draws. */
    public int count() {
        return entries.size();
    }

    /**
     * Takes each panel's UI of this frame (call after the panels' UIs updated, before the frame records): uploads what
     * changed; a texture panel whose UI changed is rendered again at the frame's start.
     */
    public void submit() {
        retiring.release();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            Ui ui = e.panel.ui();
            e.uploads.submit(ui.layers(), ui.layerCount(), ui.textures(), e.mode == Mode.TEXTURE ? e.width
                : (int) Math.ceil(e.panel.width()), e.mode == Mode.TEXTURE ? e.height : (int) Math.ceil(e.panel.height()));
            if (e.uploads.uploadedBytes() > 0) {
                e.dirty = true;
            }
        }
    }

    // ------------------------------------------------------------------------------------- texture panels, frame start

    @Override
    public void record(VkCommandBuffer commands) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.mode == Mode.TEXTURE && e.dirty) {
                render(commands, e);
                e.dirty = false;
                e.rendered = true;
                e.renders++;
            }
        }
    }

    private void render(VkCommandBuffer commands, Entry e) {
        Images.transition(commands, e.image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
            e.rendered ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            e.rendered ? VK_ACCESS_2_SHADER_SAMPLED_READ_BIT : VK_ACCESS_2_NONE,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            long color = stack.ncalloc(VkRenderingAttachmentInfo.ALIGNOF, 1, VkRenderingAttachmentInfo.SIZEOF);
            memPutInt(color + VkRenderingAttachmentInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO);
            memPutLong(color + VkRenderingAttachmentInfo.IMAGEVIEW, e.image.view());
            memPutInt(color + VkRenderingAttachmentInfo.IMAGELAYOUT, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            memPutInt(color + VkRenderingAttachmentInfo.LOADOP, VK_ATTACHMENT_LOAD_OP_CLEAR); // transparent
            memPutInt(color + VkRenderingAttachmentInfo.STOREOP, VK_ATTACHMENT_STORE_OP_STORE);
            long rendering = stack.ncalloc(VkRenderingInfo.ALIGNOF, 1, VkRenderingInfo.SIZEOF);
            memPutInt(rendering + VkRenderingInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_INFO);
            memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT, e.width);
            memPutInt(rendering + VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT + 4, e.height);
            memPutInt(rendering + VkRenderingInfo.LAYERCOUNT, 1);
            memPutInt(rendering + VkRenderingInfo.COLORATTACHMENTCOUNT, 1);
            memPutAddress(rendering + VkRenderingInfo.PCOLORATTACHMENTS, color);
            Commands.beginRendering(commands, rendering);
            long viewport = stack.ncalloc(VkViewport.ALIGNOF, 1, VkViewport.SIZEOF);
            memPutFloat(viewport + VkViewport.WIDTH, e.width);
            memPutFloat(viewport + VkViewport.HEIGHT, e.height);
            memPutFloat(viewport + VkViewport.MAXDEPTH, 1);
            Commands.setViewport(commands, 0, 1, viewport);
            long scissor = stack.ncalloc(VkRect2D.ALIGNOF, 1, VkRect2D.SIZEOF);
            memPutInt(scissor + VkRect2D.EXTENT, e.width);
            memPutInt(scissor + VkRect2D.EXTENT + 4, e.height);
            Commands.setScissor(commands, 0, 1, scissor);
        }
        e.uploads.drawLayers(commands, rawPush, e.width, e.height);
        Commands.endRendering(commands);
        Images.transition(commands, e.image.handle(), VK_IMAGE_ASPECT_COLOR_BIT,
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    }

    // -------------------------------------------------------------------------------------- direct panels, the pass

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public List<Key<?>> reads() {
        return List.of(Keys.SCENE_DEPTH);
    }

    @Override
    public Order order() {
        return Order.at(Anchor.TRANSPARENT);
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        int views = color.desc().layers();
        if (views != cameras.length) {
            throw new IllegalStateException("The view's scene colour has " + views + " layers for "
                + cameras.length + " cameras: a layer per eye");
        }
        Pipeline pipeline = pipeline(color.format().vk(), depth.format().vk(), views);
        UiPush push = new UiPush();
        g.pass("world panels").attach(color, Load.LOAD, Store.STORE).attachDepthReadOnly(depth).region()
            .record(ctx -> drawDirect(ctx, pipeline, push));
    }

    @AllowAllocation("building the graph, when its formats change")
    private Pipeline pipeline(int colorFormat, int depthFormat, int views) {
        if (world == null || worldColor != colorFormat || worldDepth != depthFormat || worldViews != views) {
            if (world != null) {
                retiring.add(world);
            }
            try (ShaderModule shaders = ShaderModule.load(device, "shaders/ui/draw.spv")) {
                world = Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "worldVertexMain")
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "worldFragmentMain").color(colorFormat)
                    .depth(depthFormat).depthTest(VK_COMPARE_OP_GREATER_OR_EQUAL, false)
                    .blend(0, Pipeline.Blend.PREMULTIPLIED).pushConstants(UiPush.BYTES).doubleSided()
                    .viewMask(views > 1 ? (1 << views) - 1 : 0).build();
            }
            worldColor = colorFormat;
            worldDepth = depthFormat;
            worldViews = views;
        }
        return world;
    }

    private void drawDirect(PassContext ctx, Pipeline pipeline, UiPush push) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.mode != Mode.DIRECT || !e.panel.transform(pose)) {
                continue;
            }
            int w = (int) Math.ceil(e.panel.width());
            int h = (int) Math.ceil(e.panel.height());
            for (int v = 0; v < cameras.length; v++) {
                matrix(cameras[v], w, h);
                for (int k = 0; k < 16; k++) {
                    matrixBytes.setAtIndex(FLOAT, 16L * v + k, (float) m[k]);
                }
            }
            e.matrix.put(0, matrixBytes, 0, 64L * cameras.length);
            push.world(e.matrix.deviceAddress());
            e.uploads.drawLayers(ctx, pipeline, push, w, h);
        }
    }

    /**
     * {@link #m}: the matrix (column-major) from a {@code w x h} panel's pixels at {@link #pose} to clip space, in
     * doubles relative to {@code camera}, through its view-projection.
     */
    private void matrix(Camera camera, int w, int h) {
        double s = pose[7];
        double qx = pose[3];
        double qy = pose[4];
        double qz = pose[5];
        double qw = pose[6];
        // the panel's axes in the world: one pixel right (+X) and one pixel down (-Y), and its top left corner
        rotate(qx, qy, qz, qw, s, 0, 0, right);
        rotate(qx, qy, qz, qw, 0, -s, 0, down);
        rotate(qx, qy, qz, qw, -w / 2.0 * s, h / 2.0 * s, 0, corner);
        double ax = pose[0] - camera.position(0) + corner[0];
        double ay = pose[1] - camera.position(1) + corner[1];
        double az = pose[2] - camera.position(2) + corner[2];
        float[] vp = camera.viewProjection();
        for (int row = 0; row < 4; row++) {
            double r0 = vp[row];
            double r1 = vp[4 + row];
            double r2 = vp[8 + row];
            double r3 = vp[12 + row];
            m[row] = r0 * right[0] + r1 * right[1] + r2 * right[2];
            m[4 + row] = r0 * down[0] + r1 * down[1] + r2 * down[2];
            m[8 + row] = 0;
            m[12 + row] = r0 * ax + r1 * ay + r2 * az + r3;
        }
    }

    /** Vector {@code x, y, z} turned by the unit quaternion {@code q}, into {@code out}. */
    private static void rotate(double qx, double qy, double qz, double qw, double x, double y, double z,
        double[] out) {
        double tx = 2 * (qy * z - qz * y);
        double ty = 2 * (qz * x - qx * z);
        double tz = 2 * (qx * y - qy * x);
        out[0] = x + qw * tx + (qy * tz - qz * ty);
        out[1] = y + qw * ty + (qz * tx - qx * tz);
        out[2] = z + qw * tz + (qx * ty - qy * tx);
    }

    /** Frees every panel's buffers and images and the pipeline: call once the GPU is done with the frames. */
    @Override
    @AllowAllocation("closing")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        graph.frameSlots().unregister(this);
        for (Entry e : entries) {
            retire(e);
        }
        entries.clear();
        if (world != null) {
            world.close();
        }
        retiring.close();
        arena.close();
    }
}

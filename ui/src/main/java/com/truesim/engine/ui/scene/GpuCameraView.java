package com.truesim.engine.ui.scene;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.Texture;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Keys;
import com.truesim.engine.ui.GpuUiRenderer;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.ui.core.draw.UiTexture;
import com.truesim.ui.core.draw.UiTextures;
import com.truesim.ui.scene.CameraView;
import com.truesim.ui.scene.CameraViewSource;
import java.util.List;
import java.util.Objects;

/**
 * A second camera rendered for a {@link CameraView} (a minimap, a rear-view mirror, a security screen; design 4.7):
 * a view of the same frame graph as the main view, at the control's pixel size, whose scene color is tone mapped as a
 * display would show it (the view's {@link DisplayTransform} in SDR: linear, 1.0 white, what the UI composites) and
 * copied into the top left of a {@link Texture} the UI's renderer samples bindlessly ({@link GpuUiRenderer#bind}) as the
 * control's UI texture: no pixels pass through the CPU.
 *
 * <p><b>No waiting.</b> The camera view's passes run in the same frame as the main view's and before the UI's pass,
 * which reads the texture after the view's copy into it: the UI shows this frame's image, and the main view never waits
 * for another frame. A frame where the camera view is skipped ({@code view().rate(n)}, {@code enabled(false)}) shows
 * the image it last rendered.
 *
 * <p><b>Size.</b> {@link #request} (the control asks each frame) sizes the view, at most the texture's size; a new size
 * rebuilds the graph once (the view's images are made at it) and takes effect in that same frame. The camera's aspect
 * follows the size through {@link #onResize}. The texture is made once at the largest size, so resizing never waits
 * for the GPU.
 *
 * <pre>{@code
 * GpuCameraView minimap = new GpuCameraView(graph, uiPlugin.renderer(), ui.textures(), "minimap", 512, 512);
 * minimap.view().add(new ClusteredForwardShading(geometry, sceneView, ...));  // what it draws
 * minimap.onResize((w, h) -> camera.setOrthographic(..., w, h)).follow(camera);
 * CameraView control = new CameraView(ui, minimap, picking, "minimap");
 * minimap.follow(control);                                                    // its picking rays
 * }</pre>
 */
public final class GpuCameraView implements CameraViewSource, AutoCloseable {

    /** What the camera view shows: its scene color tone mapped for an SDR display, linear. */
    public static final Key<Image> SHOWN = Key.image("truesim.ui.cameraView.shown");
    /** The image copied into the texture: the shown image at its top left. */
    public static final Key<Image> TEXTURE_IMAGE = Key.image("truesim.ui.cameraView.texture");

    /** Called when the view's size changes: set the camera's projection for it. */
    @FunctionalInterface
    public interface Resize {
        /** The view is now {@code width x height} pixels. */
        void resized(int width, int height);
    }

    private final FrameGraph graph;
    private final GpuUiRenderer renderer;
    private final Texture texture;
    private final UiTexture uiTexture;
    private final ViewPort view;
    private int width;
    private int height;
    private long version;
    private Resize resize;
    private CameraViews follower;
    private CameraView control;
    private double far = 100_000;
    private boolean closed;

    /**
     * A camera view named {@code name} in {@code graph}, shown by the UI drawn by {@code renderer}, its UI texture made
     * in {@code textures}, at most {@code maxWidth x maxHeight} pixels. Add what it draws to {@link #view()}.
     */
    @AllowAllocation("made when a camera view is set up")
    public GpuCameraView(FrameGraph graph, GpuUiRenderer renderer, UiTextures textures, String name, int maxWidth,
        int maxHeight) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        texture = graph.texture(name, Format.R16G16B16A16_SFLOAT, maxWidth, maxHeight);
        // the control's texture by id; its pixels are never uploaded: the renderer samples the graph texture
        uiTexture = textures.create(name, maxWidth, maxHeight, UiTexture.Format.RGBA8_SRGB);
        renderer.bind(uiTexture.id(), texture);
        width = Math.min(maxWidth, 64);
        height = Math.min(maxHeight, 64);
        view = graph.addView(name).size(width, height);
        view.add(new DisplayTransform().transform(Keys.SCENE_COLOR, SHOWN, DisplayEncoding.SDR_SRGB));
        view.add(new IntoTexture(maxWidth, maxHeight));
        view.output(TEXTURE_IMAGE, texture);
    }

    /** The graph's view the camera renders in: add its shading techniques here. */
    public ViewPort view() {
        return view;
    }

    /** The texture it renders into. */
    public Texture graphTexture() {
        return texture;
    }

    /** Calls {@code value} with the size whenever it changes (and now): set the camera's projection. Returns this. */
    public GpuCameraView onResize(Resize value) {
        resize = value;
        if (value != null) {
            value.resized(width, height);
        }
        return this;
    }

    /** Sets the control's {@code SceneView} each frame from {@code camera} (its rays pick through it). Returns this. */
    @AllowAllocation("set up")
    public GpuCameraView follow(Camera camera, CameraView shownBy) {
        control = shownBy;
        follower = camera == null || shownBy == null ? null : new CameraViews(camera, shownBy.view());
        return this;
    }

    /** How far its picking rays reach. */
    public GpuCameraView far(double metres) {
        far = metres;
        return this;
    }

    @Override
    public void request(int widthPx, int heightPx) {
        if (closed) {
            return;
        }
        int w = Math.max(1, Math.min(widthPx, texture.width()));
        int h = Math.max(1, Math.min(heightPx, texture.height()));
        if (w != width || h != height) {
            width = w;
            height = h;
            view.size(w, h); // rebuilt at the next record: this frame
            version++;
            if (resize != null) {
                resize.resized(w, h);
            }
        }
        if (follower != null && control.placed()) {
            com.truesim.ui.scene.SceneView v = control.view();
            follower.follow(v.viewportX(), v.viewportY(), v.viewportWidth(), v.viewportHeight(), far);
        }
    }

    @Override
    public UiTexture texture() {
        return closed ? null : uiTexture;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public long version() {
        return version;
    }

    /** Takes the view and the texture away (freed once no frame in flight uses them). */
    @Override
    @AllowAllocation("closing")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        renderer.bind(uiTexture.id(), null);
        graph.removeView(view);
        graph.removeTexture(texture);
    }

    /**
     * Copies the shown image into the top left of a texture-sized image (its last column and row repeated once beyond
     * it, so filtering at the edge reads no stale texels); the view's output copies that into the texture.
     */
    private static final class IntoTexture implements Technique {

        static final Id<IntoTexture> ID = Id.of("truesim.ui.cameraViewTexture", IntoTexture.class);
        private final int textureWidth;
        private final int textureHeight;

        IntoTexture(int textureWidth, int textureHeight) {
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
        }

        @Override
        public Id<? extends Technique> id() {
            return ID;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of(TEXTURE_IMAGE);
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            Image shown = g.require(SHOWN);
            Image target = g.unscaled(g.create("camera view in its texture",
                ImageDesc.of(Format.R16G16B16A16_SFLOAT, textureWidth, textureHeight)));
            g.pass("camera view into its texture").read(shown, Access.TRANSFER_SRC)
                .write(target, Access.TRANSFER_DST).record(ctx -> {
                    int w = Math.min(shown.width(), textureWidth);
                    int h = Math.min(shown.height(), textureHeight);
                    ctx.blit(shown.whole(), 0, 0, w, h, target.whole(), 0, 0, w, h, false);
                    if (w < textureWidth) {
                        ctx.blit(shown.whole(), w - 1, 0, 1, h, target.whole(), w, 0, 1, h, false);
                    }
                    if (h < textureHeight) {
                        ctx.blit(shown.whole(), 0, h - 1, w, 1, target.whole(), 0, h, w, 1, false);
                    }
                    if (w < textureWidth && h < textureHeight) {
                        ctx.blit(shown.whole(), w - 1, h - 1, 1, 1, target.whole(), w, h, 1, 1, false);
                    }
                });
            g.provide(TEXTURE_IMAGE, target);
        }
    }
}

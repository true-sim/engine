package com.truesim.engine.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.ResolutionSettings;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.lang.foreign.ValueLayout;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Motion vectors read back against the camera's projection worked out by hand, validated, for both shading
 * techniques: a quad moved by its instance, a skinned quad moved by its joints, a still quad, the floor and the sky,
 * then the camera moving. The camera at the origin looks down -Z with a 90 degree field of view over 64 pixels: 32
 * pixels per unit at distance 1, so a move of d units at depth z is 32 d / z pixels.
 */
class MotionVectorsTest {

    private static final int SIZE = 64;
    private static final float HALF = (float) Math.sqrt(0.5);

    private VulkanInstance instance;
    private VulkanDevice device;
    private GeometryPool pool;
    private GpuScene scene;
    private MeshletRenderer renderer;
    private RenderView view;
    private FrameGraph graph;
    private GpuImage target;
    private GpuBuffer pixels;
    private int quad;
    private int copy;
    private Deformation deformation;
    private final Camera camera = new Camera();

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 8));
        quad = pool.add(MeshletRendererTest.grid(1, 2, false, VertexFormat.POSITIONS_ONLY));
        MeshData skinned = GraphTechniquesTest.skinnedQuad();
        int source = pool.add(skinned);
        copy = pool.addDeformed(source);
        deformation = pool.deformation();
        deformation.add(copy, 2);
        scene = new GpuScene(device, pool, 16);
        renderer = new MeshletRenderer(device, scene, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
        view = renderer.createView(ViewOptions.of(256, 1 << 12).withOcclusion(true));
        graph = new FrameGraph(device, 2);
        target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        pixels = GpuBuffer.hostVisible(device, SIZE * SIZE * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            graph.close();
            pixels.close();
            target.close();
            renderer.close();
            scene.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** Records a frame and reads the motion back: x and y in pixels per pixel. */
    private float[] frame() {
        device.submitAndWait(device.graphics(), commands -> {
            graph.record(commands);
            try (MemoryStack stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(SIZE, SIZE, 1);
                vkCmdCopyImageToBuffer(commands, target.handle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    pixels.handle(), region);
            }
        });
        float[] motion = new float[2 * SIZE * SIZE];
        for (int i = 0; i < motion.length; i++) {
            motion[i] = Float.float16ToFloat(pixels.memory().getAtIndex(ValueLayout.JAVA_SHORT, i));
        }
        return motion;
    }

    /** Joints of the skinned quad moved {@code dx} along x, as its bounds say. */
    private void pose(double dx) {
        for (int joint = 0; joint < 2; joint++) {
            deformation.joint(copy, joint, 1, 0, 0, dx, 0, 1, 0, 0, 0, 0, 1, 0);
        }
        deformation.bounds(copy, -1 + dx, 0, -1, 1 + dx, 0, 1, Math.abs(dx));
    }

    /** A quad (2 x 2 scaled by 0.5: a unit square) facing the camera, its center at x, 0, z. */
    private void facing(int id, int mesh, float x, float z) {
        scene.set(id, mesh, x, 0, z, HALF, 0, 0, HALF, 0.5f, 0.5f, 0.5f);
    }

    private static void expect(float[] motion, int x, int y, double mx, double my, String what) {
        int p = y * SIZE + x;
        assertThat((double) motion[2 * p]).as("%s, x", what).isCloseTo(mx, within(0.05));
        assertThat((double) motion[2 * p + 1]).as("%s, y", what).isCloseTo(my, within(0.05));
    }

    private void movingObjectsAndTheCameraReadBackTheirMotion(SceneGeometry geometry, RenderView ids,
        Function<SceneView, Technique> shading) {
        scene.set(0, quad, 0, -1, -4, 0, 0, 0, 1, 10, 10, 10); // the floor, still
        facing(1, quad, 1.2f, -4);                           // still
        facing(2, quad, -1.5f, -4);                          // moved by its instance
        facing(3, copy, 0, -8);                              // moved by its joints
        pose(0);
        camera.set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
        view.set(camera, 1, MeshletRenderer.MODE_LIT);
        ids.set(camera, 1, MeshletRenderer.MODE_LIT);
        SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
        main.port().add(shading.apply(main))
            .add(new MotionVectors(geometry, ids, main, VK_FORMAT_D32_SFLOAT))
            .output(Keys.MOTION, new ExternalImage("motion", SIZE, SIZE, Format.R16G16_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()))
            .output(Keys.SCENE_COLOR, new ExternalImage("color", SIZE, SIZE, Format.R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(color.handle(), color.view()));
        float[] motion = frame();
        motion = frame();
        expect(motion, 41, 32, 0, 0, "still, the first frames");
        // The instance moves 0.5 right, the joints 2 (1 in the world: scaled by 0.5): where they were is left of here.
        facing(2, quad, -1.0f, -4);
        pose(2);
        motion = frame();
        expect(motion, 24, 32, -32 * 0.5 / 4, 0, "the quad its instance moved");
        expect(motion, 36, 32, -32 * 1.0 / 8, 0, "the quad its joints moved");
        expect(motion, 41, 32, 0, 0, "the still quad");
        expect(motion, 32, 60, 0, 0, "the floor");
        expect(motion, 32, 2, 0, 0, "the sky");
        // Nothing moves: none anywhere, the skinned quad's vertices as they were.
        motion = frame();
        expect(motion, 24, 32, 0, 0, "the quad, still now");
        expect(motion, 36, 32, 0, 0, "the skinned quad, still now");
        // The camera moves 0.25 right: everything was right of here, by 32 x 0.25 / depth; the sky not at all.
        camera.position(0.25, 0, 0);
        motion = frame();
        expect(motion, 22, 32, 32 * 0.25 / 4, 0, "the quad, the camera moving");
        expect(motion, 34, 32, 32 * 0.25 / 8, 0, "the skinned quad, the camera moving");
        expect(motion, 40, 32, 32 * 0.25 / 4, 0, "the still quad, the camera moving");
        expect(motion, 32, 2, 0, 0, "the sky, the camera moving");
        // The floor's point at pixel (32, 60): along its ray to y = -1, back through the camera as it was.
        double ndcY = (60.5 / SIZE) * 2 - 1;
        double depth = 1 / Math.abs(ndcY); // where the ray meets y = -1, at 90 degrees: |y| / z = |ndc y|
        expect(motion, 32, 60, 32 * 0.25 / depth, 0, "the floor, the camera moving");
        // The camera stops: no motion from the next frame on, though nothing about the camera changed in it.
        motion = frame();
        expect(motion, 40, 32, 0, 0, "the still quad, the camera stopped");
        expect(motion, 32, 60, 0, 0, "the floor, the camera stopped");
        camera.position(0, 0, 0);
    }

    private GpuImage color;

    @Test
    void motionVectorsOfTheVisibilityBufferShareItsIds() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        try (GpuImage image = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT)) {
            color = image;
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            movingObjectsAndTheCameraReadBackTheirMotion(geometry, view, main -> new VisibilityBufferShading(
                geometry, main, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT));
            assertThat(graph.passes().stream().filter(p -> p.startsWith("meshlet ids early")).count())
                .as("one ID draw: the shading's").isOne();
            assertThat(graph.passes()).contains("motion vectors", "instances kept");
            // Every frame skinning the quad anew, its vertices kept aside: nothing allocated per frame (an ID draw's
            // iterator over a list did, 16 bytes a phase, until the compiler took it away: pitfalls.md)
            pose(1);
            // A first count discarded, as warm-up: its 400 frames read 7 allocating on main and 10 with displacement's
            // code to compile, the next 400 3 on both, then 0 (pitfalls.md)
            ReferencePathTracerTest.framesAllocating(device, graph, 400);
            assertThat(ReferencePathTracerTest.framesAllocating(device, graph, 400)).as("frames allocating")
                .isLessThan(8);
            device.waitIdle();
        }
    }

    @Test
    void motionReachesTheFrameBeforesRenderRegion() {
        assumeThat(device.primitiveIds()).as("primitive IDs in fragment shaders").isTrue();
        try (GpuImage image = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT)) {
            color = image;
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            scene.set(0, quad, 0, -1, -4, 0, 0, 0, 1, 10, 10, 10); // the floor; nothing moves
            scene.removeFrom(1);
            camera.set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            view.set(camera, 1, MeshletRenderer.MODE_LIT);
            SceneView main = new SceneView(graph.addView("main").size(SIZE, SIZE), view, camera);
            main.port().add(new VisibilityBufferShading(geometry, main, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_FORMAT_D32_SFLOAT)).add(new MotionVectors(geometry, view, main, VK_FORMAT_D32_SFLOAT))
                .output(Keys.MOTION, new ExternalImage("motion", SIZE, SIZE, Format.R16G16_SFLOAT,
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
            graph.settings().set(ResolutionSettings.DYNAMIC, true).set(ResolutionSettings.HEADROOM, 1.0f);
            frame();
            float[] motion = frame();
            expect(motion, 40, 50, 0, 0, "the full region, still");
            // Rendered at half the size: the floor still, its pixels where the frame before's region had them, twice
            // as far from the corner: a history of the frame before is read at the pixel plus the motion.
            main.port().renderScale(0.5f);
            motion = frame();
            // The output shows the region at the view's size, its texels doubled: region pixel (16, 30) at (32, 60)
            expect(motion, 32, 60, 16.5, 30.5, "half the region after the full one");
            motion = frame();
            expect(motion, 32, 60, 0, 0, "half the region again");
            main.port().renderScale(1);
            motion = frame();
            expect(motion, 40, 50, -20.25, -25.25, "the full region after half");
            device.waitIdle();
        }
    }

    @Test
    void motionVectorsOfForwardShading() {
        try (GpuImage image = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT)) {
            color = image;
            // Forward shading culls its view itself: the IDs from a second view of the same camera
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            RenderView ids = renderer.createView(ViewOptions.of(256, 1 << 12).withOcclusion(true));
            movingObjectsAndTheCameraReadBackTheirMotion(geometry, ids, main -> new ClusteredForwardShading(
                geometry, main, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT));
            device.waitIdle();
        }
    }
}

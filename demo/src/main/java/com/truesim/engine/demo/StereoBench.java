package com.truesim.engine.demo;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GpuTimings;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.shadows.CascadedShadowMaps;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a stereo frame's GPU time goes: the demo's scene from two eyes 64 mm apart, each a view of its own at a Quest
 * 3's eye size, offscreen, passes timed and averaged over many frames, then grouped by what they do (which work each
 * eye repeats). {@code ./gradlew :engine-demo:stereoBench}; {@code --args="visibility"} shades through the visibility
 * buffer.
 */
public final class StereoBench {

    private static final int WIDTH = 2064;
    private static final int HEIGHT = 2208;
    private static final int FRAMES = 300;

    private StereoBench() {
    }

    public static void main(String[] args) {
        boolean visibility = List.of(args).contains("visibility");
        int color = VK_FORMAT_R16G16B16A16_SFLOAT;
        int depth = VK_FORMAT_D32_SFLOAT;
        try (VulkanInstance instance = VulkanInstance.builder().applicationName("stereo bench").surfaces(false)
                 .build();
             VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
             FrameGraph graph = new FrameGraph(device, 2).timings(true);
             DemoScene world = new DemoScene(device, graph.frameSlots(), color, depth)) {
            int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            List<AutoCloseable> owned = new ArrayList<>();
            Camera[] eyes = {new Camera(), new Camera()};
            for (int e = 0; e < 2; e++) {
                GpuImage target = new GpuImage(device, WIDTH, HEIGHT, VK_FORMAT_R8G8B8A8_UNORM, usage,
                    VK_IMAGE_ASPECT_COLOR_BIT);
                owned.add(target);
                ViewPort port = graph.addView(e == 0 ? "left" : "right").size(WIDTH, HEIGHT);
                SceneView view = world.geometry().createView(port, eyes[e], ViewOptions.of(1 << 14, 1 << 22)
                    .withOcclusion(true)).set(1, MeshletRenderer.MODE_LIT);
                port.add(visibility ? new VisibilityBufferShading(world.geometry(), view, color, depth)
                        : new ClusteredForwardShading(world.geometry(), view, color, depth))
                    .add(new ClusteredLights(world.lights(), view))
                    .add(new CascadedShadowMaps(world.geometry(), world.lights(), 0, 0, view, 3, 2048,
                        ViewOptions.shadow(1 << 16, 1 << 22), 150))
                    .output(Keys.SCENE_COLOR, new ExternalImage("eye " + e, WIDTH, HEIGHT, Format.R8G8B8A8_UNORM,
                        usage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                        .set(target.handle(), target.view()));
            }
            Map<String, double[]> sums = new LinkedHashMap<>(); // per pass: total, frames timed
            for (int frame = 0; frame < FRAMES; frame++) {
                world.update(frame / 72.0);
                for (int e = 0; e < 2; e++) {
                    double offset = (e == 0 ? -0.032 : 0.032);
                    eyes[e].position(30 + offset, 6, 30).set(-0.7, -0.15, -0.7, 0, 1, 0, Math.PI / 2, WIDTH, HEIGHT,
                        0.05);
                }
                graph.beginFrame();
                graph.submit(0, 0);
                GpuTimings timings = graph.timings();
                if (frame > 30 && timings != null) {
                    Map<String, Integer> seen = new java.util.HashMap<>(); // both eyes have passes of one name
                    for (int i = 0; i < timings.count(); i++) {
                        double ms = timings.millis(i);
                        if (!Double.isNaN(ms)) {
                            int n = seen.merge(timings.pass(i), 1, Integer::sum);
                            String name = n == 1 ? timings.pass(i) : timings.pass(i) + " (#" + n + ")";
                            double[] sum = sums.computeIfAbsent(name, k -> new double[2]);
                            sum[0] += ms;
                            sum[1]++;
                        }
                    }
                }
            }
            device.waitIdle();
            report(sums, visibility);
            for (AutoCloseable resource : owned) {
                resource.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void report(Map<String, double[]> sums, boolean visibility) {
        Map<String, Double> groups = new LinkedHashMap<>();
        double total = 0;
        System.out.printf(Locale.ROOT, "Stereo, %d x %d per eye, %s shading, mean GPU ms per pass:%n", WIDTH, HEIGHT,
            visibility ? "visibility buffer" : "forward");
        for (Map.Entry<String, double[]> entry : sums.entrySet()) {
            double ms = entry.getValue()[0] / entry.getValue()[1];
            total += ms;
            System.out.printf(Locale.ROOT, "  %-50s %7.3f%n", entry.getKey(), ms);
            groups.merge(group(entry.getKey()), ms, Double::sum);
        }
        System.out.println("By kind:");
        for (Map.Entry<String, Double> entry : groups.entrySet()) {
            System.out.printf(Locale.ROOT, "  %-30s %7.3f ms  %4.1f%%%n", entry.getKey(), entry.getValue(),
                100 * entry.getValue() / total);
        }
        System.out.printf(Locale.ROOT, "  %-30s %7.3f ms%n", "total", total);
    }

    private static String group(String pass) {
        if (pass.startsWith("meshlet depth") || pass.contains("cascade")) {
            return "shadow cascades (per eye)";
        }
        if (pass.startsWith("meshlet culling") || pass.contains("late culling") || pass.contains("selection")) {
            return "culling";
        }
        if (pass.startsWith("depth pyramid")) {
            return "depth pyramid (per eye)";
        }
        if (pass.startsWith("meshlet") || pass.startsWith("classic")) {
            return "camera draws (per eye)";
        }
        if (pass.startsWith("light clusters")) {
            return "light clusters (per eye)";
        }
        if (pass.contains("visibility") || pass.contains("material")) {
            return "visibility shading (per eye)";
        }
        if (pass.startsWith("output")) {
            return "output copies";
        }
        return "other (" + pass + ")";
    }
}

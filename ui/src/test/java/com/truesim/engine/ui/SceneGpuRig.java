package com.truesim.engine.ui;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

import com.truesim.common.math.color.Color;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.materials.UnlitParameters;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;

/**
 * A {@link GpuRig} with a 3D scene in its main view: unlit boxes and quads drawn by the meshlet renderer with forward
 * shading, the view's UI composited by the display transform into what is read back ({@link #SHOWN}), as a window
 * would show it. Further views ({@link #view}) draw the same scene through cameras of their own.
 */
final class SceneGpuRig implements AutoCloseable {

    /** The main view's result: the scene tone mapped with the UI over it, linear (the window's sRGB copy encodes). */
    static final Key<Image> SHOWN = Key.image("test.shown");

    final GpuRig rig;
    final GeometryPool pool;
    final GpuScene scene;
    final MeshletRenderer renderer;
    final Materials materials;
    final Camera camera = new Camera();
    final SceneView main;
    final int box;
    final int quad;
    final MeshData boxData;
    final MeshData quadData;

    /** A rig of {@code width x height}, or null (the test skipped) without a GPU. */
    static SceneGpuRig open(int width, int height) {
        return new SceneGpuRig(GpuRig.open(width, height));
    }

    private SceneGpuRig(GpuRig rig) {
        this.rig = rig;
        pool = new GeometryPool(rig.device, GeometryPool.Capacity.of(1 << 14, 256, 8));
        boxData = box();
        box = pool.add(boxData);
        quadData = quad();
        quad = pool.add(quadData);
        scene = new GpuScene(rig.device, pool, 1024);
        renderer = new MeshletRenderer(rig.device, scene, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT);
        materials = new Materials(rig.device, 32);
        scene.setMaterials(materials);
        main = view(rig.view, camera);
        rig.view.add(new DisplayTransform().transform(Keys.SCENE_COLOR, SHOWN, DisplayEncoding.SDR_SRGB));
        rig.show(SHOWN);
    }

    /** The scene seen through {@code through} in {@code port}, forward shaded, cleared to a dark blue. */
    SceneView view(com.truesim.engine.graph.ViewPort port, Camera through) {
        RenderView view = renderer.createView(ViewOptions.of(4096, 1 << 16));
        view.set(through, 1, MeshletRenderer.MODE_LIT);
        SceneView sceneView = new SceneView(port, view, through).set(1, MeshletRenderer.MODE_LIT);
        port.add(new ClusteredForwardShading(SceneGeometry.meshlets(renderer), sceneView,
            VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT).clearColor(0.02f, 0.03f, 0.06f));
        return sceneView;
    }

    /** An unlit material of linear colour {@code r, g, b}. */
    int unlit(float r, float g, float b) {
        return materials.add(new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColor(new Color(r, g, b, 1)))
            .feature(UnlitParameters.DEFAULTS));
    }

    /** Instance {@code id}: a box of half size {@code half} at {@code x, y, z}. */
    void box(int id, int material, double x, double y, double z, float half) {
        scene.set(id, box, material, x, y, z, 0, 0, 0, 1, half, half, half);
    }

    /** A 2 x 2 x 2 cube about the origin. */
    static MeshData box() {
        float[] positions = new float[6 * 4 * 3];
        float[] normals = new float[6 * 4 * 3];
        int[] indices = new int[6 * 6];
        int[][] axes = {{0, 1, 2}, {1, 2, 0}, {2, 0, 1}};
        int face = 0;
        for (int[] a : axes) {
            for (int sign : new int[] {1, -1}) {
                float[] n = new float[3];
                float[] u = new float[3];
                float[] v = new float[3];
                n[a[0]] = sign;
                u[a[1]] = sign;
                v[a[2]] = 1;
                float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
                for (int c = 0; c < 4; c++) {
                    for (int k = 0; k < 3; k++) {
                        positions[3 * (face * 4 + c) + k] = n[k] + corners[c][0] * u[k] + corners[c][1] * v[k];
                        normals[3 * (face * 4 + c) + k] = n[k];
                    }
                }
                int[] q = {0, 1, 2, 0, 2, 3};
                for (int i = 0; i < 6; i++) {
                    indices[face * 6 + i] = face * 4 + q[i];
                }
                face++;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 24).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(indices).buildFlat();
    }

    /** A 2 x 2 quad in the xy plane facing +z. */
    static MeshData quad() {
        float[] positions = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 4).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(new int[] {0, 1, 2, 0, 2, 3}).buildFlat();
    }

    @Override
    public void close() {
        rig.device.waitIdle();
        rig.graph.close();
        renderer.close();
        scene.setMaterials(null);
        materials.close();
        scene.close();
        pool.close();
        rig.close();
    }
}

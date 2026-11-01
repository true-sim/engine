package com.truesim.engine.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.graph.Rendering;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.gltf.Gltf;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The assets plugin: a model loaded by path from a mounted folder, through the engine's frames. */
class AssetsTest {

    @TempDir
    Path dir;

    /** A glTF triangle, its buffer inline. */
    private static String triangle() {
        ByteBuffer b = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}) {
            b.putFloat(f);
        }
        return """
            {"asset": {"version": "2.0"}, "scenes": [{"nodes": [0]}], "nodes": [{"name": "tri", "mesh": 0}],
             "buffers": [{"byteLength": 36, "uri": "data:application/octet-stream;base64,%s"}],
             "bufferViews": [{"buffer": 0, "byteLength": 36}],
             "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
             "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}]}
            """.formatted(Base64.getEncoder().encodeToString(b.array()));
    }

    @Test
    void modelsLoadByPathThroughTheEnginesFrames() throws Exception {
        Files.createDirectories(dir.resolve("models"));
        Files.writeString(dir.resolve("models/tri.gltf"), triangle());
        Assets plugin = new Assets().mount("game", dir).materials(16);
        assertThatIllegalStateException().isThrownBy(plugin::cache).withMessageContaining("not yet");
        Engine engine;
        try {
            engine = Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    app.use(new Rendering().headless(true), plugin);
                }
            });
        } catch (StartupFailure e) {
            assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
        try (engine) {
            Assets assets = engine.get(Assets.KEY);
            assertThat(assets.resources()).isNotNull();
            assertThat(engine.settings().get(Assets.BUDGET)).isEqualTo(512);
            engine.settings().set(Assets.BUDGET, 64);
            new Gltf(assets.paths()).register();
            AssetRef<ModelData> ref = assets.ref(RenderAssets.MODEL, "game://models/tri.gltf");
            assets.cache().acquire(ref);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (assets.cache().get(ref) == null && assets.cache().failure(ref) == null) {
                engine.step();
                assertThat(System.nanoTime()).isLessThan(deadline);
            }
            assertThat(assets.cache().failure(ref)).isNull();
            ModelData model = assets.cache().get(ref);
            assertThat(model.nodes()).extracting(ModelData.Node::name).containsExactly("tri");
            AssetRef<MeshData> mesh = model.nodes().get(0).mesh();
            assets.cache().acquire(mesh);
            while (assets.cache().get(mesh) == null && assets.cache().failure(mesh) == null) {
                engine.step();
                assertThat(System.nanoTime()).isLessThan(deadline);
            }
            assertThat(assets.cache().get(mesh).triangleCount()).isEqualTo(1);
        }
    }
}

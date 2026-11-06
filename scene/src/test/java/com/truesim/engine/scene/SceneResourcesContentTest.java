package com.truesim.engine.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.Textures;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Content techniques hold through the scene resources (core prerequisite 7.5): textures, meshes, blocks of materials
 * and assets of types with an uploader, loaded through the asset cache, put on the GPU, found by their refs and freed
 * with their last hold; validated.
 */
class SceneResourcesContentTest {

    /** A lookup table, a type the resources do not know: uploaded by the test's uploader. */
    record Table(int value) {
    }

    static final AssetType<Table> TABLE = AssetType.of("test.table", Table.class, t -> 4);
    static final Generator<Integer, Table> TABLES = (value, seed) -> new Table(value);
    static final AssetRef<Table> SEVEN = AssetRef.generated(TABLE, TABLES, 7, 1);
    static final AssetRef<TextureData> WHITE = AssetRef.generated(RenderAssets.TEXTURE, SceneSyncTest.PLAINS,
        new SceneSyncTest.Plain(-1), 1);
    static final AssetRef<TextureData> BLUE = AssetRef.generated(RenderAssets.TEXTURE, SceneSyncTest.PLAINS,
        new SceneSyncTest.Plain(0xffff0000), 1);

    private VulkanInstance instance;
    private VulkanDevice device;
    private final FrameSlots slots = new FrameSlots(2);
    private GeometryPool pool;
    private Materials materials;
    private Textures textures;
    private TaskScheduler tasks;
    private AssetCache assets;
    private SceneResources resources;

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 10, 16, 4), slots);
        materials = new Materials(device, 16, slots);
        textures = new Textures(device, slots);
        tasks = new TaskScheduler();
        assets = new AssetCache(tasks);
        resources = new SceneResources(assets, pool, materials, textures);
    }

    @AfterEach
    void stop() {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            resources.close();
            assets.close();
            tasks.close();
            textures.close();
            materials.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    private void load() {
        assertThat(resources.awaitLoaded(tasks, Duration.ofSeconds(10))).isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (resources.loadingCount() > 0 && System.nanoTime() < deadline) {
            tasks.drainCompleted();
            Thread.onSpinWait();
        }
        assertThat(resources.loadingCount()).isZero();
    }

    @Test
    void heldTexturesAndMeshesComeOntoTheGpuAndLeaveWithTheirLastHold() {
        long before = resources.residency();
        resources.hold(WHITE);
        resources.hold(WHITE); // twice: two holders
        resources.hold(SceneSyncTest.SQUARE);
        assertThat(resources.texture(WHITE)).isEqualTo(-1); // not loaded yet: nothing
        load();
        int slot = resources.texture(WHITE);
        assertThat(slot).isNotNegative();
        assertThat(textures.contains(slot)).isTrue();
        assertThat(resources.mesh(SceneSyncTest.SQUARE)).isNotNegative();
        assertThat(resources.uploadedMeshCount()).isEqualTo(1);
        long arrived = resources.residency();
        assertThat(arrived).isNotEqualTo(before);
        // one hold let go of: still there, nothing changed
        resources.release(WHITE);
        assertThat(resources.texture(WHITE)).isEqualTo(slot);
        assertThat(resources.residency()).isEqualTo(arrived);
        // the last: gone, and said so
        resources.release(WHITE);
        resources.release(SceneSyncTest.SQUARE);
        assertThat(resources.texture(WHITE)).isEqualTo(-1);
        assertThat(resources.mesh(SceneSyncTest.SQUARE)).isEqualTo(-1);
        assertThat(resources.meshCount()).isZero();
        assertThat(resources.residency()).isNotEqualTo(arrived);
        assertThat(resources.texture(null)).isEqualTo(-1);
        assertThat(resources.mesh(null)).isEqualTo(-1);
        assertThatIllegalStateException().isThrownBy(() -> resources.release(WHITE));
        assertThatIllegalStateException().isThrownBy(() -> resources.release(SceneSyncTest.SQUARE));
    }

    @Test
    void aTextureHeldAndUsedByAMaterialStaysUntilBothLetGo() {
        AssetRef<MaterialData> textured = AssetRef.generated(RenderAssets.MATERIAL,
            (Integer unused, long seed) -> MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
                .texture("baseColorTexture", BLUE).build(), 0, 1);
        int first = resources.holdMaterials(List.of(textured), 2);
        resources.hold(BLUE);
        load();
        resources.flush();
        int slot = resources.texture(BLUE);
        assertThat(slot).isNotNegative();
        assertThat(resources.materialOf(first)).isEqualTo(textured);
        assertThat(resources.materialOf(first + 1)).isEqualTo(textured); // past the list: the last material
        // the material lets go: the hold keeps it
        resources.releaseMaterials(first);
        assertThat(resources.materialOf(first)).isNull();
        assertThat(resources.texture(BLUE)).isEqualTo(slot);
        resources.release(BLUE);
        assertThat(resources.texture(BLUE)).isEqualTo(-1);
        assertThatIllegalArgumentException().isThrownBy(() -> resources.releaseMaterials(first));
        assertThatIllegalArgumentException().isThrownBy(() -> resources.releaseMaterials(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> resources.holdMaterials(List.of(), 0));
    }

    @Test
    void assetsOfOtherTypesGoThroughTheirUploader() {
        List<String> log = new ArrayList<>();
        SceneResources.SampledUploader<Table> uploader = new SceneResources.SampledUploader<>() {
            @Override
            public int upload(Table data) {
                log.add("upload " + data.value());
                return 40 + data.value();
            }

            @Override
            public void remove(int slot) {
                log.add("remove " + slot);
            }
        };
        resources.sampled(TABLE, uploader);
        resources.hold(SEVEN);
        resources.hold(SEVEN);
        load();
        assertThat(resources.texture(SEVEN)).isEqualTo(47);
        resources.release(SEVEN);
        assertThat(log).containsExactly("upload 7");
        resources.release(SEVEN);
        assertThat(log).containsExactly("upload 7", "remove 47");
        assertThat(resources.texture(SEVEN)).isEqualTo(-1);
        // held again once in the cache: uploaded at once
        resources.hold(SEVEN);
        load();
        resources.hold(AssetRef.generated(TABLE, TABLES, 8, 1)); // and another one while it is
        load();
        assertThat(resources.texture(AssetRef.generated(TABLE, TABLES, 8, 1))).isEqualTo(48);
        // what is not allowed
        assertThatIllegalStateException().isThrownBy(() -> resources.release(AssetRef.generated(TABLE, TABLES, 9, 1)));
        assertThatIllegalStateException().isThrownBy(() -> resources.sampled(TABLE, uploader));
        assertThatIllegalArgumentException().isThrownBy(() -> resources.sampled(RenderAssets.TEXTURE, null));
        assertThatIllegalArgumentException().isThrownBy(() -> resources.hold(
            AssetRef.generated(AssetType.of("test.unknown", Table.class, t -> 4), TABLES, 1, 1)));
    }

    @Test
    void anUploaderFailingIsReportedAndNotWaitedFor() {
        List<AssetRef<?>> failed = new ArrayList<>();
        resources.diagnostics(new SceneResources.Diagnostics() {
            @Override
            public void assetFailed(AssetRef<?> asset, Throwable cause) {
                failed.add(asset);
            }
        });
        resources.sampled(TABLE, new SceneResources.SampledUploader<>() {
            @Override
            public int upload(Table data) {
                throw new IllegalStateException("no room");
            }

            @Override
            public void remove(int slot) {
                throw new AssertionError("never uploaded");
            }
        });
        resources.hold(SEVEN);
        load();
        assertThat(failed).containsExactly(SEVEN);
        assertThat(resources.texture(SEVEN)).isEqualTo(-1);
        resources.release(SEVEN);
    }
}

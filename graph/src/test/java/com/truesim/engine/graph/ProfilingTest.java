package com.truesim.engine.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.engine.app.AppConfig;
import com.truesim.engine.app.Application;
import com.truesim.engine.app.Engine;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.app.StartupFailure;
import com.truesim.engine.app.profile.Profiler;
import com.truesim.engine.app.profile.ProfilerSettings;
import com.truesim.engine.vulkan.Format;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Profiling plugin in a headless engine with validation: it follows the settings, puts the GPU's passes on the
 * CPU's clock after their submit, counts pipeline statistics, starts the counters and the runtime's markers, marks
 * hitches.
 */
class ProfilingTest {

    private static final Key<Image> OUT = Key.image("test.profiled");

    /** A frame of two passes into a texture. */
    private static final class Frames implements Plugin {
        static final PluginKey<Frames> KEY = PluginKey.of("test.profiling.frames", Frames.class);

        @Override
        public PluginKey<Frames> key() {
            return KEY;
        }

        @Override
        public List<PluginKey<?>> requires() {
            return List.of(Rendering.KEY);
        }

        @Override
        public void start(Stage stage, Startup startup) {
            if (stage == Stage.GRAPH) {
                FrameGraph graph = startup.get(Rendering.KEY).graph();
                graph.add(new FrameGraphTest.Fill(OUT, 64, 1, 0, 0, 1))
                    .output(OUT, graph.texture("test.profiled", Format.R8G8B8A8_UNORM, 64, 64));
            }
        }
    }

    /** On the GPU as it is, and as one without the profiler's optional capabilities (the fallbacks' paths). */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void itFollowsTheSettingsAndPlacesGpuPassesAfterTheirSubmit(boolean masked) throws Exception {
        Engine engine;
        try {
            engine = Engine.create(new Application() {
                @Override
                public void configure(AppConfig app) {
                    Rendering rendering = new Rendering().headless(true).validation(true);
                    if (masked) {
                        rendering.without(com.truesim.engine.vulkan.VulkanDevice.MEMORY_BUDGET,
                            com.truesim.engine.vulkan.VulkanDevice.CALIBRATED_TIMESTAMPS,
                            com.truesim.engine.vulkan.VulkanDevice.PIPELINE_STATISTICS);
                    }
                    app.use(rendering, new Frames(), new Profiling());
                }
            });
        } catch (StartupFailure e) {
            assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
        try (engine) {
            Profiling p = engine.get(Profiling.KEY);
            assertThat(p.key()).isEqualTo(Profiling.KEY);
            assertThat(engine.registry().find("profiler.view")).isNotNull();
            engine.step();
            assertThat(p.on()).isFalse();
            assertThat(p.graph().timings()).isNull();
            var s = engine.settings();
            s.set(ProfilerSettings.OVERLAY, true);
            s.set(ProfilerSettings.VIEW, true);
            s.set(ProfilerSettings.PIPELINE_STATISTICS, true);
            s.set(ProfilerSettings.RUNTIME, true);
            s.set(ProfilerSettings.OS, true);
            s.set(ProfilerSettings.COUNTER_INTERVAL, 10);
            s.set(ProfilerSettings.HITCH_MIN, 1f);
            for (int i = 0; i < 60; i++) {
                engine.step();
            }
            Thread.sleep(30); // a hitch
            for (int i = 0; i < 10; i++) {
                engine.step();
                if (p.hitch(Profiler.lastFrame())) {
                    break;
                }
            }
            assertThat(p.on()).isTrue();
            assertThat(p.detailed()).isTrue();
            assertThat(Profiler.zones()).isTrue();
            assertThat(p.meanInterval()).isPositive();
            assertThat(p.gpuFrame()).isPositive();
            assertThat(p.gpuEnd()).isGreaterThan(p.gpuStart());
            assertThat(p.passCount()).isPositive();
            boolean fill = false;
            for (int i = 0; i < p.passCount(); i++) {
                if (p.passName(i).equals("fill profiled")) {
                    fill = true;
                    assertThat(p.passEnd(i)).isGreaterThanOrEqualTo(p.passStart(i));
                    assertThat(p.passOnCompute(i)).isFalse();
                }
            }
            assertThat(fill).isTrue();
            assertThat(p.gpuBusy()).isBetween(0.0, 100.0);
            assertThat(p.frameStartBegin()).isNotNegative();
            assertThat(p.frameStartEnd()).isNotNegative();
            assertThat(p.clock()).isNotNull();
            assertThat(p.runtime()).isNotNull();
            GpuTimings t = p.graph().timings();
            assertThat(t.statisticsOn()).isEqualTo(p.graph().device().pipelineStatistics());
            if (masked) {
                assertThat(t.statisticsOn()).isFalse();
                assertThat(p.clock().calibrated()).isFalse();
                assertThat(p.counters().unavailable()).anyMatch(u -> u.contains("VK_EXT_memory_budget"));
            }
            assertThat(t.nanosPerTick()).isPositive();
            assertThat(GpuTimings.STATISTIC_NAMES).hasSize(GpuTimings.STATISTICS);
            Thread.sleep(50);
            assertThat(p.counters().started()).isTrue();
            assertThat(p.counters().count()).isPositive();
            // the runtime's markers off again; the view off
            s.set(ProfilerSettings.RUNTIME, false);
            s.set(ProfilerSettings.VIEW, false);
            engine.step();
            s.set(ProfilerSettings.OVERLAY, false);
            engine.step();
            assertThat(p.on()).isFalse();
            assertThat(Profiler.frames()).isFalse();
        }
    }
}

// Shared by the engine modules: LWJGL with natives for the desktop platforms.
val lwjglVersion by extra("3.4.3")
val lwjglNatives by extra(
    listOf("natives-windows", "natives-windows-arm64", "natives-linux", "natives-linux-arm64", "natives-macos", "natives-macos-arm64"),
)

subprojects {
    // LWJGL and mapped GPU memory use native access.
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

// The GPU test modules run their tests concurrently, classes and methods, a test per processor: on CI's software
// Vulkan (lavapipe) a test spends most of its time compiling pipelines, on one thread, so one test at a time left three
// of the four processors idle (docs/pitfalls.md, "Tests compile their pipelines anew"). Each test makes its own instance
// and device, so they share nothing on the GPU side. At most four at once: a real GPU gains little from more. Benchmarks
// and screenshots run one at a time, as measured.
val concurrentTests = setOf("engine-graph", "engine-scene", "engine-render")
val measuring = providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
    || providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true"
val testThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
configure(subprojects.filter { it.name in concurrentTests }) {
    tasks.withType<Test>().configureEach {
        if (!measuring) {
            systemProperty("junit.jupiter.execution.parallel.enabled", "true")
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
            systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
            systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
            systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "$testThreads")
            maxHeapSize = "1g" // several tests' scenes at once
        }
    }
}

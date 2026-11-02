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

// engine-render and engine-scene run their tests concurrently, classes and methods. On CI's software Vulkan (lavapipe)
// a test spends most of its time compiling pipelines on its own thread: one test at a time kept one of the four
// processors busy (docs/pitfalls.md, "On lavapipe, tests are pipeline compiles"). Each test makes its own instance and
// device, so they share nothing on the GPU side. engine-render, the longest, gets a test per processor (at most four:
// a real GPU gains little from more, -Ptruesim.testThreads=<n> sets it), engine-scene half as many, while it runs
// beside it; the other modules are short and stay serial. Benchmarks and screenshots run one at a time, as measured.
val measuring = providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
    || providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true"
val testThreads = providers.gradleProperty("truesim.testThreads").map(String::toInt)
    .getOrElse(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
val concurrentTests = mapOf("engine-render" to testThreads, "engine-scene" to (testThreads / 2).coerceAtLeast(1))
configure(subprojects.filter { (concurrentTests[it.name] ?: 1) > 1 }) {
    val threads = concurrentTests.getValue(name)
    tasks.withType<Test>().configureEach {
        if (!measuring) {
            systemProperty("junit.jupiter.execution.parallel.enabled", "true")
            systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
            systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
            systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
            systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "$threads")
            maxHeapSize = "1g" // several tests' scenes at once
        }
    }
}

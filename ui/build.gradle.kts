plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Engine UI for true-sim: the UI's renderer (a frame graph technique), its input actions and pointers."

dependencies {
    api(project(":engine-render"))
    api(project(":engine-input"))
    api("com.truesim.ui:ui-core:0.1.0")
}

// The UI shader's constants (ui/primitive.slang) as Java, checked against ui-core's Primitive by a test.
slang {
    pushPackage = "com.truesim.engine.ui.layout"
    pushBase = "com.truesim.engine.vulkan.PushConstants"
    constantModules = listOf("ui/primitive")
}

// GPU benchmarks (TRUESIM_BENCHMARK=true) and screenshots: measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
        || providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}

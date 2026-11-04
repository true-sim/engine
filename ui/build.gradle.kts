plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Engine UI for true-sim: the UI's renderer (a frame graph technique), its input actions and pointers."

dependencies {
    api(project(":engine-render"))
    api(project(":engine-input"))
    api("com.truesim.ui:ui-core:0.3.0")
    api("com.truesim.ui:ui-controls:0.3.0") // the Ui the plugin runs
    testImplementation(testFixtures("com.truesim.ui:ui-controls:0.3.0")) // the galleries the screenshots show
    testImplementation("com.truesim.ui:ui-text-native:0.3.0")
    testImplementation("com.truesim.ui:ui-fonts-cjk:0.3.0") // bundled fonts: the same text on every machine
    testImplementation("com.truesim.ui:ui-fonts-emoji:0.3.0") // colour emoji in the text field test
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

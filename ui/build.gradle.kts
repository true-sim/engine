plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Engine UI for true-sim: the UI's renderer (a frame graph technique), its input actions and pointers."

dependencies {
    api(project(":engine-render"))
    api(project(":engine-input"))
    implementation(project(":engine-xr")) // the headset's controls, for the XR rays' default bindings
    api("com.truesim.ui:ui-core:0.5.0")
    api("com.truesim.ui:ui-controls:0.5.0") // the Ui the plugin runs
    api("com.truesim.ui:ui-scene:0.5.0") // the scene bridge: picking, world panels, camera views
    api("com.truesim.ui:ui-gizmo:0.5.0") // gizmos, whose occlusion the view's depth answers (and their tunables)
    api("com.truesim.ui:ui-console:0.5.0") // the developer console over the engine's settings and log
    api("com.truesim.ui:ui-props:0.5.0") // property panels: the settings screen, material parameters
    api("com.truesim.ui:ui-profiler:0.5.0") // the profiler's views: the frame graph overlay, the detailed profiler
    runtimeOnly("com.truesim.ui:ui-charts:0.5.0") // charts' tunables (a TunableSource service) as settings
    testImplementation(testFixtures("com.truesim.ui:ui-controls:0.5.0")) // the galleries the screenshots show
    testImplementation(testFixtures("com.truesim.ui:ui-charts:0.5.0"))
    testImplementation(testFixtures("com.truesim.ui:ui-props:0.5.0"))
    testImplementation(testFixtures("com.truesim.ui:ui-console:0.5.0"))
    testImplementation(testFixtures("com.truesim.ui:ui-gizmo:0.5.0"))
    testImplementation(testFixtures("com.truesim.ui:ui-scene:0.5.0")) // the scene rig and fake camera source
    testImplementation("com.truesim.scene:scene-meshbuild:0.63.0") // meshes for picking and panels
    testImplementation(project(":engine-bench")) // the benchmark scenes (UiScenes) the batching benchmark compares
    testImplementation("com.truesim.ui:ui-text-native:0.5.0")
    testImplementation("com.truesim.ui:ui-fonts-cjk:0.5.0") // bundled fonts: the same text on every machine
    testImplementation("com.truesim.ui:ui-fonts-emoji:0.5.0") // colour emoji in the text field test
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

plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine scene interaction for true-sim: pointers on scene nodes (picking, events, hover, drag, " +
    "selection) through scene's picking model, input actions and the frame's cameras; XR rays come from the xr " +
    "module as a pointer source of its own."

dependencies {
    api(project(":engine-render")) // the frame's cameras and windows
    api(project(":engine-input")) // input actions
    api("com.truesim.scene:scene-core:0.68.0") // components, Pickable, ScenePicker
}

// The picking benchmark (TRUESIM_BENCHMARK=true): measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}

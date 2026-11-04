plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine scene interaction for true-sim: pointers on scene nodes (picking, events, hover, drag, " +
    "selection) through scene's picking model, input actions and the frame's cameras."

dependencies {
    api(project(":engine-xr")) // the renderer's cameras, input actions, the headset's controls for XR rays
    api("com.truesim.scene:scene-core:0.64.1") // components, Pickable, ScenePicker
}

// The picking benchmark (TRUESIM_BENCHMARK=true): measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}

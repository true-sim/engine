plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine VR layer for true-sim: headsets through OpenXR, behind the engine's own API."

val lwjglVersion: String by rootProject.extra
val lwjglNatives: List<String> by rootProject.extra

dependencies {
    api(project(":engine-render"))
    implementation("org.lwjgl:lwjgl-openxr")
    for (natives in lwjglNatives.filter { !it.contains("macos") }) { // OpenXR has no macOS loader
        runtimeOnly("org.lwjgl:lwjgl-openxr::$natives")
    }
}

// The headset's code runs only with a headset connected (HeadsetTest, with TRUESIM_XR=true): CI has none to cover it.
coverage {
    minimum.set(0.0)
}

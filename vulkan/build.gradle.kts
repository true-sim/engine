plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Engine Vulkan layer for true-sim: instances, GPUs, devices, memory, surfaces and swapchains."

val lwjglVersion: String by rootProject.extra
val lwjglNatives: List<String> by rootProject.extra

dependencies {
    api(project(":engine-platform"))
    api(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    api("org.lwjgl:lwjgl-vulkan")
    api("org.lwjgl:lwjgl-vma")
    for (natives in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl-vma::$natives")
    }
    // The Vulkan loader comes with the GPU driver on Windows and Linux; macOS needs MoltenVK.
    runtimeOnly("org.lwjgl:lwjgl-vulkan::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-vulkan::natives-macos-arm64")
}

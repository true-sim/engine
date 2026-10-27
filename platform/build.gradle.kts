plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine platform layer for true-sim: SDL3 windows, displays, events and input devices."

val lwjglVersion: String by rootProject.extra
val lwjglNatives: List<String> by rootProject.extra

dependencies {
    api(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    api("org.lwjgl:lwjgl")
    api("org.lwjgl:lwjgl-sdl")
    for (natives in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl::$natives")
        runtimeOnly("org.lwjgl:lwjgl-sdl::$natives")
    }
}

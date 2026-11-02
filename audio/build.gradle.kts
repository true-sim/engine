plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine audio for true-sim: the mixer on an allocation-free audio thread, offline rendering, SDL3 output."

val lwjglVersion: String by rootProject.extra
val lwjglNatives: List<String> by rootProject.extra

dependencies {
    api(project(":engine-platform"))
    api("com.truesim.scene:scene-audio:0.58.0")
    implementation("org.lwjgl:lwjgl-sdl")
    for (natives in lwjglNatives) {
        runtimeOnly("org.lwjgl:lwjgl-sdl::$natives")
    }
}

// Golden renders: regenerate with -Ptruesim.audio.goldens=write (then listen to the diff).
tasks.test {
    providers.gradleProperty("truesim.audio.goldens").orNull?.let { systemProperty("truesim.audio.goldens", it) }
    systemProperty("truesim.audio.resources", file("src/test/resources/com/truesim/engine/audio").absolutePath)
}

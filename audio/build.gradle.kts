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
    api("com.truesim.scene:scene-animation:0.58.0") // AudioBindings: animation events and curves
    api("com.truesim.scene:scene-effects:0.58.0") // AudioBindings: effects starting and bursting
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

// The real-device probe (AudioDeviceProbe): TRUESIM_AUDIO=true ./gradlew :engine-audio:test --tests '*AudioDeviceProbe*'
// [-Ptruesim.audio.seconds=600] [-Ptruesim.audio.garbage=true] [-Ptruesim.audio.zgc=true] [-Ptruesim.audio.minimised=true]
tasks.test {
    for (name in listOf("seconds", "garbage", "minimised", "proofs")) {
        providers.gradleProperty("truesim.audio.$name").orNull?.let { systemProperty("truesim.audio.$name", it) }
    }
    if (providers.gradleProperty("truesim.audio.zgc").orNull == "true") {
        jvmArgs("-XX:+UseZGC")
    }
    if (providers.environmentVariable("TRUESIM_AUDIO").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}

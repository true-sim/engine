plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine audio for true-sim: the mixer on an allocation-free audio thread, offline rendering, SDL3 output."

val lwjglVersion: String by rootProject.extra
val lwjglNatives: List<String> by rootProject.extra

dependencies {
    api(project(":engine-platform"))
    api("com.truesim.scene:scene-audio:0.64.1")
    api("com.truesim.scene:scene-animation:0.64.1") // AudioBindings: animation events and curves
    api("com.truesim.scene:scene-effects:0.64.1") // AudioBindings: effects starting and bursting
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

// The SOFA converter (§2.9, decision 2): HDF5 is read at build time only, through jHDF in this source set, never at
// runtime. ./gradlew :engine-audio:convertHrtf -Ptruesim.audio.sofa=<file.sofa> -Ptruesim.audio.hrtf=<out.hrtf>
// [-Ptruesim.audio.directions=2000] [-Ptruesim.audio.taps=128] [-Ptruesim.audio.credit=<text>]
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

dependencies {
    "toolsImplementation"("io.jhdf:jhdf:0.13.0")
}

tasks.register<JavaExec>("convertHrtf") {
    group = "audio"
    description = "Converts a SOFA file (SimpleFreeFieldHRIR) to an .hrtf set."
    classpath = tools.runtimeClasspath
    mainClass = "com.truesim.engine.audio.SofaConverter"
    args(
        providers.gradleProperty("truesim.audio.sofa").orNull ?: "",
        providers.gradleProperty("truesim.audio.hrtf").orNull ?: "build/converted.hrtf",
        providers.gradleProperty("truesim.audio.directions").orNull ?: "0",
        providers.gradleProperty("truesim.audio.taps").orNull ?: "128",
        providers.gradleProperty("truesim.audio.credit").orNull ?: "converted from SOFA",
    )
}

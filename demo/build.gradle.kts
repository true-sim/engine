plugins {
    id("truesim.application-conventions")
}

description = "The engine's demo: a scene to try settings, HDR, dynamic resolution, several windows and VR."

dependencies {
    implementation(project(":engine-xr"))
    implementation(project(":engine-scene"))
    implementation(project(":engine-audio"))
    implementation("com.truesim.scene:scene-meshbuild:0.64.1")
    implementation("com.truesim.scene:scene-gltf:0.64.1")
    // The UI gallery (UiGallery): the engine's UI over the galleries of ui's test fixtures
    implementation(project(":engine-ui")) // the UI gallery, and the stream benchmark's window (the profiler, a line of text)
    implementation("com.truesim.ui:ui-charts:0.9.0")
    implementation("com.truesim.ui:ui-text-native:0.9.0")
    implementation("com.truesim.ui:ui-fonts:0.9.0")
    implementation("com.truesim.ui:ui-fonts-cjk:0.9.0")
    implementation("com.truesim.ui:ui-fonts-emoji:0.9.0")
    implementation(testFixtures("com.truesim.ui:ui-controls:0.9.0"))
    implementation(testFixtures("com.truesim.ui:ui-charts:0.9.0"))
    implementation(testFixtures("com.truesim.ui:ui-props:0.9.0"))
    implementation(testFixtures("com.truesim.ui:ui-profiler:0.9.0"))
    testImplementation("org.lwjgl:lwjgl-sdl") // the gallery's test pushes mouse and key events through SDL
}

application {
    mainClass = "com.truesim.engine.demo.Demo"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC") // generational ZGC: pauses the audio queue covers
    // -Pcharacter=<glTF-Sample-Assets>/Models/Fox/glTF/Fox.gltf: an animated fox among the rocks
    providers.gradleProperty("character").orNull?.let { systemProperty("truesim.demo.character", it) }
}

// A demo to run, not a library: nothing to cover.
coverage {
    minimum.set(0.0)
}

// Where a stereo frame's GPU time goes (StereoBench): ./gradlew :engine-demo:stereoBench [--args="visibility"]
tasks.register<JavaExec>("stereoBench") {
    group = "application"
    description = "Times the demo's scene rendered for two eyes, per pass."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.engine.demo.StereoBench"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    providers.gradleProperty("rocks").orNull?.let { systemProperty("truesim.demo.rocks", it) }
    providers.gradleProperty("png").orNull?.let { systemProperty("truesim.demo.png", it) }
    providers.gradleProperty("casterDetail").orNull?.let { systemProperty("truesim.demo.casterDetail", it) }
    providers.gradleProperty("character").orNull?.let { systemProperty("truesim.demo.character", it) }
    providers.gradleProperty("eye").orNull?.let { systemProperty("truesim.demo.eye", it) }
    providers.gradleProperty("pngFrames").orNull?.let { systemProperty("truesim.demo.pngFrames", it) }
    providers.gradleProperty("frames").orNull?.let { systemProperty("truesim.demo.frames", it) }
    // -Peffect=x,y,z: the demo's particle effects at a point (g+h: h above the ground)
    providers.gradleProperty("effect").orNull?.let { systemProperty("truesim.demo.effect", it) }
    // -Plights=1024: that many point lights, the circling six and the rest scattered near the middle
    providers.gradleProperty("lights").orNull?.let { systemProperty("truesim.demo.lights", it) }
    // -Pfoxes=1000 -Pcrowd=<path to Fox.gltf> [-PfoxLod=true]: a crowd of animated foxes, what animating it costs
    providers.gradleProperty("foxes").orNull?.let { systemProperty("truesim.demo.foxes", it) }
    providers.gradleProperty("crowd").orNull?.let { systemProperty("truesim.demo.crowd", it) }
    providers.gradleProperty("foxLod").orNull?.let { systemProperty("truesim.demo.foxLod", it) }
    providers.gradleProperty("rebuildEvery").orNull?.let { systemProperty("truesim.demo.rebuildEvery", it) }
}

// Hitches while content streams (StreamBench): ./gradlew :engine-demo:streamBench
// [--args=window]: the same fly-over in a window with the profiler (F3, Shift+F3), until closed or -Pseconds=n
// -Prange=n: cells in range each way (default 3, the window 10); -Pspeed=n: metres a second (10, the window 60)
tasks.register<JavaExec>("streamBench") {
    group = "application"
    description = "Streams cells of meshes, textures and materials while frames run, and reports frame times."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.engine.demo.StreamBench"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
    providers.gradleProperty("jfr").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    providers.gradleProperty("seconds").orNull?.let { systemProperty("truesim.demo.seconds", it) }
    providers.gradleProperty("range").orNull?.let { systemProperty("truesim.demo.range", it) } // cells each way
    providers.gradleProperty("speed").orNull?.let { systemProperty("truesim.demo.speed", it) } // metres a second
}

// The effects showcase (Showcase): ./gradlew :engine-demo:showcase -Pcrowd=<path to Fox.gltf> [--args=measure]
tasks.register<JavaExec>("showcase") {
    group = "application"
    description = "A hundred foxes, fire, fireworks and debris at once: frames to PNG files, or frame times measured."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.engine.demo.Showcase"
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
    for (name in listOf("crowd", "character", "foxes", "rocks", "camera", "size", "frames", "first", "every", "out",
        "stills", "exposure", "torches", "sparks", "glass", "async")) {
        providers.gradleProperty(name).orNull?.let { systemProperty("truesim.demo.$name", it) }
    }
}

// The UI gallery (UiGallery): ./gradlew :engine-demo:uiGallery -- every gallery of the UI in a window, a 3D scene with
// picking, selection and gizmos, and the live profiler (F3, Shift+F3) over the app's own frames
tasks.register<JavaExec>("uiGallery") {
    group = "application"
    description = "Opens the UI gallery: every control gallery, docking, the console, the profiler and a 3D scene."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.engine.demo.UiGallery"
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+UseZGC", "-Xmx2g")
    systemProperty("slf4j.provider", "com.truesim.engine.ui.console.ConsoleLogProvider") // the log in the console
    // -Pseconds=n quits after n seconds; -Ptour=true visits every page and opens the profiler's views (a smoke run)
    providers.gradleProperty("seconds").orNull?.let { args("--seconds=$it") }
    providers.gradleProperty("tour").orNull?.let { if (it == "true") args("--tour") }
    // -Pshots=<dir>: during a tour, what the window shows of each page as PNGs
    providers.gradleProperty("shots").orNull?.let { systemProperty("truesim.gallery.shots", it) }
}

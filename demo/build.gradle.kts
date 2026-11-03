plugins {
    id("truesim.application-conventions")
}

description = "The engine's demo: a scene to try settings, HDR, dynamic resolution, several windows and VR."

dependencies {
    implementation(project(":engine-xr"))
    implementation(project(":engine-scene"))
    implementation(project(":engine-audio"))
    implementation("com.truesim.scene:scene-meshbuild:0.58.1")
    implementation("com.truesim.scene:scene-gltf:0.58.1")
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
tasks.register<JavaExec>("streamBench") {
    group = "application"
    description = "Streams cells of meshes, textures and materials while frames run, and reports frame times."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.engine.demo.StreamBench"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
    providers.gradleProperty("jfr").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
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

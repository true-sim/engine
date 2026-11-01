plugins {
    id("truesim.application-conventions")
}

description = "The engine's demo: a scene to try settings, HDR, dynamic resolution, several windows and VR."

dependencies {
    implementation(project(":engine-xr"))
    implementation("com.truesim.scene:scene-meshbuild:0.25.0")
}

application {
    mainClass = "com.truesim.engine.demo.Demo"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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

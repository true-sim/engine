plugins {
    id("truesim.application-conventions")
}

description = "The engine's demo: a scene to try settings, HDR, dynamic resolution, several windows and VR."

dependencies {
    implementation(project(":engine-xr"))
    implementation("com.truesim.scene:scene-meshbuild:0.22.0")
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

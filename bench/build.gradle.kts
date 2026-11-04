plugins {
    id("truesim.application-conventions")
}

description = "The engine's benchmark suites, run as programs: the UI's (design section 11) on the GPU and the CPU."

dependencies {
    implementation(project(":engine-ui"))
    implementation("com.truesim.ui:ui-charts:0.3.0")
    implementation("com.truesim.ui:ui-text-native:0.3.0")
    implementation("com.truesim.ui:ui-fonts-cjk:0.3.0") // bundled fonts: the same text on every machine
    implementation(testFixtures("com.truesim.ui:ui-controls:0.3.0")) // the controls gallery, an idle UI
    implementation(testFixtures("com.truesim.ui:ui-console:0.3.0")) // the console, flooded
}

application {
    mainClass = "com.truesim.engine.bench.UiBench"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// A benchmark to run, not a library: nothing to cover.
coverage {
    minimum.set(0.0)
}

// The UI's suite: ./gradlew :engine-bench:uiBench [-Pquick=true]; writes docs/benchmarks-ui.md.
tasks.register<JavaExec>("uiBench") {
    group = "application"
    description = "Runs the UI's benchmarks (section 11 of ui's design) and writes docs/benchmarks-ui.md."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.truesim.engine.bench.UiBench"
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xmx4g")
    args(rootProject.file("docs/benchmarks-ui.md").absolutePath)
    providers.gradleProperty("quick").orNull?.let { systemProperty("truesim.bench.quick", it) }
    providers.gradleProperty("only").orNull?.let { systemProperty("truesim.bench.only", it) } // -Ponly=console
    providers.gradleProperty("spikes").orNull?.let { systemProperty("truesim.bench.spikes", it) }
}

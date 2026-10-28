plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Engine renderer for true-sim: GPU-driven meshlet rendering of scene's meshes."

dependencies {
    api(project(":engine-vulkan"))
    api("com.truesim.scene:scene-assets:0.17.2")
    implementation("com.truesim.common:common-math:0.4.0")
    testImplementation("com.truesim.scene:scene-meshbuild:0.17.2")
}

// The benchmark (TRUESIM_BENCHMARK=true) builds and draws meshes of millions of triangles.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
        || providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}

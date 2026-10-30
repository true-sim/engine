plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang") // test shaders only: a custom pass importing engine-render's modules, as a game's would
}

description = "Engine scene bridge for true-sim: draws scene's renderables, following its changes and asset cache."

dependencies {
    api(project(":engine-render"))
    api("com.truesim.scene:scene-render:0.22.0")
    testImplementation("com.truesim.scene:scene-meshbuild:0.22.0")
}

// The test shaders' material records (a game's own type, as in a project of its own), as Java.
slang {
    javaPackage = "com.truesim.engine.scene.materials"
}

// The benchmark (TRUESIM_BENCHMARK=true) builds and draws meshes of millions of triangles.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}

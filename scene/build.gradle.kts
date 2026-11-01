plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang") // test shaders only: a custom pass importing engine-render's modules, as a game's would
}

description = "Engine scene bridge for true-sim: draws scene's renderables, following its changes and asset cache."

dependencies {
    api(project(":engine-render"))
    api("com.truesim.scene:scene-render:0.36.0")
    testImplementation("com.truesim.scene:scene-meshbuild:0.36.0")
    testImplementation("com.truesim.scene:scene-gltf:0.36.0")
}

// The test shaders' material records (a game's own type, as in a project of its own), as Java.
slang {
    javaPackage = "com.truesim.engine.scene.materials"
    pushBase = "com.truesim.engine.vulkan.PushConstants" // push layouts are writers too
}

// The Khronos sample models, rendered to build/samples when given (SampleModelsRender):
// ./gradlew :engine-scene:test --tests '*SampleModelsRender*' -PgltfSamples=<glTF-Sample-Assets/Models> [-PgltfModels=A,B]
tasks.test {
    providers.gradleProperty("gltfSamples").orNull?.let { systemProperty("truesim.gltf.samples", it) }
    providers.gradleProperty("gltfModels").orNull?.let { systemProperty("truesim.gltf.models", it) }
    providers.gradleProperty("gltfZoom").orNull?.let { systemProperty("truesim.gltf.zoom", it) }
    providers.gradleProperty("gltfEnvironment").orNull?.let { systemProperty("truesim.gltf.environment", it) }
    providers.gradleProperty("gltfAnimation").orNull?.let { systemProperty("truesim.gltf.animation", it) }
    providers.gradleProperty("gltfClip").orNull?.let { systemProperty("truesim.gltf.clip", it) }
}

// The benchmark (TRUESIM_BENCHMARK=true) builds and draws meshes of millions of triangles.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}

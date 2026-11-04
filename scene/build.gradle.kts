plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang") // test shaders only: a custom pass importing engine-render's modules, as a game's would
}

description = "Engine scene bridge for true-sim: draws scene's renderables, following its changes and asset cache."

dependencies {
    api(project(":engine-render"))
    api("com.truesim.scene:scene-render:0.64.1")
    testImplementation("com.truesim.scene:scene-meshbuild:0.64.1")
    testImplementation("com.truesim.scene:scene-gltf:0.64.1")
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
    providers.gradleProperty("gltfBackground").orNull?.let { systemProperty("truesim.gltf.background", it) }
    providers.gradleProperty("gltfAnimation").orNull?.let { systemProperty("truesim.gltf.animation", it) }
    providers.gradleProperty("gltfClip").orNull?.let { systemProperty("truesim.gltf.clip", it) }
    providers.gradleProperty("gltfTransparency").orNull?.let { systemProperty("truesim.gltf.transparency", it) }
}

// Global illumination's test scenes (gi.GiReferences, TRUESIM_SCREENSHOTS=true): -PgiOut=<directory> for the images,
// -PgiScenes=a,b, -PgiSamples=n; -PgiSponza=<Sponza.gltf> adds Sponza (a local asset under its own licence: never
// committed, only read where it is).
tasks.test {
    providers.gradleProperty("giOut").orNull?.let { systemProperty("truesim.gi.out", it) }
    providers.gradleProperty("giScenes").orNull?.let { systemProperty("truesim.gi.scenes", it) }
    providers.gradleProperty("giSamples").orNull?.let { systemProperty("truesim.gi.samples", it) }
    providers.gradleProperty("giSponza").orNull?.let { systemProperty("truesim.gi.sponza", it) }
    providers.gradleProperty("giBounces").orNull?.let { systemProperty("truesim.gi.bounces", it) }
    providers.gradleProperty("giReferences").orNull?.let { systemProperty("truesim.gi.references", it) }
    providers.gradleProperty("giVariants").orNull?.let { systemProperty("truesim.gi.variants", it) }
    providers.gradleProperty("giParts").orNull?.let { systemProperty("truesim.gi.parts", it) }
    providers.gradleProperty("giPasses").orNull?.let { systemProperty("truesim.gi.passes", it) }
    providers.gradleProperty("giTrials").orNull?.let { systemProperty("truesim.gi.trials", it) }
    providers.gradleProperty("giCurves").orNull?.let { systemProperty("truesim.gi.curves", it) }
    providers.gradleProperty("giSetups").orNull?.let { systemProperty("truesim.gi.setups", it) }
    providers.gradleProperty("giSettings").orNull?.let { systemProperty("truesim.gi.settings", it) }
}

// The benchmark (TRUESIM_BENCHMARK=true) builds and draws meshes of millions of triangles.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}

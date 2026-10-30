plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Engine renderer for true-sim: GPU-driven meshlet rendering of scene's meshes."

dependencies {
    api(project(":engine-graph"))
    api("com.truesim.scene:scene-assets:0.22.0")
    api("com.truesim.common:common-math:0.5.0") // the material records' colors and vectors
    testImplementation("com.truesim.scene:scene-meshbuild:0.22.0")
}

// The material records of src/main/slang (materials.slang), as Java: MetallicRoughnessParameters and the others.
slang {
    javaPackage = "com.truesim.engine.render.materials"
    pushPackage = "com.truesim.engine.render.layout"
    pushBase = "com.truesim.engine.vulkan.PushConstants" // push layouts are writers too
    constantModules = listOf("bindless", "lights", "materials", "meshlets/common") // BindlessConstants and others
}

// The benchmark (TRUESIM_BENCHMARK=true) builds and draws meshes of millions of triangles.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
        || providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false } // measure the code, not its coverage probes
    }
}

// The bindless storage images are declared twice on one binding, as float and as unsigned integer images: Slang's
// overlap check (E39001) would reject that. Indexing them per pixel (NonUniformResourceIndex) adds SPIR-V capabilities
// beyond the base profile, which Slang adds itself but reports (E41012). Both are turned off by src/main/slang's
// slangc.arguments, for these shaders and those of any project importing these modules.

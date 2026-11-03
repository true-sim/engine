plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine input for true-sim: actions in groups, bindings saved as settings, players, contexts and filters."

dependencies {
    api(project(":engine-platform"))
    implementation("com.truesim.common:common-math:0.6.0") // filters' curves and smoothing
    testImplementation("org.lwjgl:lwjgl-sdl") // events pushed through SDL to the desktop
}

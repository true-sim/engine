plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine scene bridge for true-sim: draws scene's renderables, following its changes and asset cache."

dependencies {
    api(project(":engine-render"))
    api("com.truesim.scene:scene-render:0.18.0")
    testImplementation("com.truesim.scene:scene-meshbuild:0.17.2")
}

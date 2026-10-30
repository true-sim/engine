plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine frame graph for true-sim: techniques plugged together by the results they provide and need."

dependencies {
    api(project(":engine-vulkan"))
    api("com.truesim.common:common-jobs:0.3.2")
    implementation("com.truesim.common:common-graph:0.1.0") // resolution by key, culling and order
}

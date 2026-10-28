plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine frame graph for true-sim: techniques plugged together by the results they provide and need."

dependencies {
    api(project(":engine-vulkan"))
    api("com.truesim.common:common-jobs:0.3.0")
}

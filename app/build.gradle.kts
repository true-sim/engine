plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Engine application core for true-sim: the lifecycle, the loop, plugins and their startup stages."

dependencies {
    api(project(":engine-settings"))
    api("com.truesim.common:common-jobs:0.3.2")
}

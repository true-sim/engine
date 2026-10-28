pluginManagement {
    // Develop against a local build-logic checkout: -Ptruesim.build-logic=../build-logic
    providers.gradleProperty("truesim.build-logic").orNull?.let { includeBuild(it) }
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/true-sim/build-logic") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

plugins {
    id("truesim.settings") version "0.4.0"
}

rootProject.name = "engine"

include("engine-platform", "engine-vulkan")
project(":engine-platform").projectDir = file("platform")
project(":engine-vulkan").projectDir = file("vulkan")

providers.gradleProperty("truesim.build-logic").orNull?.let { includeBuild(it) }

dependencyResolutionManagement {
    repositories {
        for (repo in listOf("build-logic", "common-math", "common-jobs")) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}

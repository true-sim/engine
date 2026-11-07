pluginManagement {
    // Use ../build-logic when present; override with -Ptruesim.build-logic=<path> (or false).
    providers.gradleProperty("truesim.build-logic").orElse("../build-logic").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
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
    id("truesim.settings") version "0.17.0"
}

rootProject.name = "engine"

include("engine-settings", "engine-app", "engine-platform", "engine-input", "engine-vulkan", "engine-graph", "engine-render", "engine-scene", "engine-audio", "engine-xr", "engine-interaction", "engine-ui")
project(":engine-settings").projectDir = file("settings")
project(":engine-app").projectDir = file("app")
project(":engine-platform").projectDir = file("platform")
project(":engine-input").projectDir = file("input")
project(":engine-vulkan").projectDir = file("vulkan")
project(":engine-graph").projectDir = file("graph")
project(":engine-render").projectDir = file("render")
project(":engine-scene").projectDir = file("scene")
project(":engine-audio").projectDir = file("audio")
project(":engine-xr").projectDir = file("xr")
project(":engine-interaction").projectDir = file("interaction")
project(":engine-ui").projectDir = file("ui")

val truesimRepos = listOf("build-logic", "common-math", "common-state", "common-jobs", "common-graph", "scene", "ui")
for (repo in truesimRepos) {
    providers.gradleProperty("truesim.$repo").orElse("../$repo").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
}

dependencyResolutionManagement {
    repositories {
        for (repo in truesimRepos) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}

// Shared by the engine modules: LWJGL with natives for the desktop platforms.
val lwjglVersion by extra("3.4.3")
val lwjglNatives by extra(
    listOf("natives-windows", "natives-windows-arm64", "natives-linux", "natives-linux-arm64", "natives-macos", "natives-macos-arm64"),
)

subprojects {
    // LWJGL and mapped GPU memory use native access.
    tasks.withType<Test>().configureEach {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

listOf("iphoneos", "iphonesimulator").forEach { sdk ->
    tasks.create<Exec>("buildCrypto${sdk.capitalize()}") {
        group = "build"

        commandLine(
            "xcodebuild",
            "-project", "PhoenixCrypto.xcodeproj",
            "-target", "PhoenixCrypto",
            "-sdk", sdk,
            "BITCODE_GENERATION_MODE=bitcode"
        )
        workingDir(projectDir)

        inputs.files(
            fileTree("$projectDir/PhoenixCrypto.xcodeproj") { exclude("**/xcuserdata") },
            fileTree("$projectDir/PhoenixCrypto")
        )
        outputs.files(
            fileTree("$projectDir/build/Release-${sdk}")
        )
    }
}

tasks.create<Delete>("clean") {
    group = "build"

    delete("$projectDir/build")
}

plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.protobuf)
}


protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }

    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api(libs.protobuf.javalite)
}

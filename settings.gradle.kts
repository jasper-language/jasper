pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "jasper"

include(":jasper-compiler")
include(":jasper-llvm")

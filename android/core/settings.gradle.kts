// Build independiente del núcleo (Kotlin/JVM puro, sin dependencias Android).
// Se incluye en el build Android mediante includeBuild("core") y puede
// compilarse/probarse en cualquier JVM:  ./gradlew -p core test
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "cadfield-core"
include(":cad", ":versioning")

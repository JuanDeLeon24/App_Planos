pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CADFieldViewerPro"

// Núcleo Kotlin puro (DXF, geometría, medición, versionado). Build independiente y testeable en JVM.
includeBuild("core")

include(":app")

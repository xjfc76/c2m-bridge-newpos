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
        // SumUp SDK repository (for later)
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.sumup.com/releases") }
    }
}

rootProject.name = "CouchToMouthBridge"
include(":app")

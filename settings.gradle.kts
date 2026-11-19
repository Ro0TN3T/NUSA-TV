pluginManagement {
    repositories {
        google()
        // Google-hosted mirror reduces transient Maven Central HTTP 429 failures.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Try the mirror before Maven Central to avoid rate-limit failures.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}
rootProject.name = "NusaTV"
include(":app")

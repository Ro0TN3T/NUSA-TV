pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central.
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        // Secondary mirror in case the first mirror is temporarily unavailable.
        maven {
            url = uri("https://maven.aliyun.com/repository/central/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Prefer mirrors before the rate-limited Maven Central endpoint.
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/central/")
        }
        mavenCentral()
    }
}

rootProject.name = "NusaTV"
include(":app", ":baselineprofile")

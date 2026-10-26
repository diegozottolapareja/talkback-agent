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

rootProject.name = "talkback-agent"

include(":core")
// -PcoreOnly builds only the pure-Kotlin module (no Android SDK / Google Maven needed).
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
    include(":probe")
}

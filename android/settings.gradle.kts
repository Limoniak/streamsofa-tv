pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lumen"

include(
    ":app",
    ":baselineprofile",
    ":core-bridge",
    ":data",
    ":designsystem",
    ":feature:auth",
    ":feature:home",
    ":feature:player",
    ":feature:settings",
)

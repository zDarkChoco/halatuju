pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "HalaTuju"

include(":app")
include(":core:model")
include(":core:storage")
include(":core:location")
include(":core:map")
include(":core:routing")
include(":core:geocoding")
include(":core:navigation")
include(":core:voice")
include(":feature:search")
include(":feature:navigation")
include(":feature:settings")

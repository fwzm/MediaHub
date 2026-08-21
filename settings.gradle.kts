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

rootProject.name = "MediaHub"

include(":app")

include(":core:common")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:security")
include(":core:logging")
include(":core:ui")

include(":player:engine")
include(":player:compatibility")

include(":provider:api")
include(":provider:base")
include(":provider:emby")
include(":provider:jellyfin")
include(":provider:webdav")
include(":provider:local")

include(":metadata")

include(":feature:home")
include(":feature:server")
include(":feature:library")
include(":feature:detail")
include(":feature:search")
include(":feature:settings")
include(":feature:player")

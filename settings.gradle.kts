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

rootProject.name = "LinguaReader"

include(":app")
include(":core")
include(":data")
include(":engine:onnx")
include(":engine:supertonic")
include(":engine:alignment")
include(":importer")
include(":shared") // framework para la app iOS (ios/)

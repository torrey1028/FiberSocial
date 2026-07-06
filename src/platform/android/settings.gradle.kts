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
rootProject.name = "FiberSocial"
include(":app")
include(":common")
project(":common").projectDir = file("../../common")
include(":composeApp")
project(":composeApp").projectDir = file("../../compose")

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "recoow"
include(":app", ":core:domain", ":core:data", ":core:designsystem")
include(":feature:home", ":feature:statistics", ":feature:settings")

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
include(":core:calendar", ":core:backup", ":core:scene", ":core:location")
include(":core:database")
include(":core:network", ":core:sync")

include(":core:media", ":feature:media")

include(":feature:bills")

include(":core:notifications")
include(":feature:reminders")

include(":benchmark")

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "chiara-app-android"
include(":app", ":core:model", ":core:domain", ":core:network", ":core:designsystem")
// Pending modules (see docs/MIGRATION.md): :core:data, :feature:*, :ml:asr, :ml:llm

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "chiara-app-android"
include(":app", ":core:model", ":core:domain", ":core:designsystem")
// Pending modules (see docs/MIGRATION.md): :core:network, :core:data, :feature:*, :ml:asr, :ml:llm

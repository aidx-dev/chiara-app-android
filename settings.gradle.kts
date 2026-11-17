pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // sherpa-onnx (on-device speech recognition) publishes its AAR only on GitHub Releases.
        ivy {
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("v[revision]/sherpa-onnx-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("com.k2fsa") }
        }
    }
}
rootProject.name = "chiara-app-android"
include(":app", ":core:model", ":core:domain", ":core:network", ":core:data", ":core:designsystem", ":core:ai")
// Pending modules (see docs/MIGRATION.md): :feature:*, :ml:asr, :ml:llm

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Backend coordinates come from local.properties / secrets.properties (never committed).
val secrets = Properties().apply {
    listOf("local.properties", "secrets.properties").map(rootProject::file).filter { it.exists() }
        .forEach { f -> f.inputStream().use(::load) }
}
fun secret(name: String, default: String = "") = secrets.getProperty(name) ?: default

android {
    namespace = "com.chiarasystems.chiara"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.chiarasystems.chiara"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        debug {
            // The Android emulator reaches the host's local Supabase through 10.0.2.2.
            buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL_DEBUG", "http://10.0.2.2:54321")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY_DEBUG")}\"")
            // Fake backend with invented data (DemoBackend). On unless DEMO_BACKEND=false in local.properties.
            buildConfigField("boolean", "DEMO_BACKEND", secret("DEMO_BACKEND", "true"))
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")
            buildConfigField("boolean", "DEMO_BACKEND", "false")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api" }
}
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}

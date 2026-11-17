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
        // minSdk 31 devices are 64-bit: drop the 32-bit native libs (MediaPipe) to halve the APK.
        // `-Pabis=x86_64` builds a smaller APK for the emulator.
        ndk { abiFilters += (project.findProperty("abis") as String? ?: "arm64-v8a,x86_64").split(",") }
    }
    // Release signing: keystore.properties (git-ignored) with storeFile, storePassword, keyAlias, keyPassword.
    // Without it the release APK is built unsigned (CI, local checks).
    val keystoreFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }
    if (keystoreFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
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
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api" }
}
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ai"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    // biometric 1.1 pulls an old fragment whose FragmentActivity rejects the request codes of the Activity Result API.
    implementation(libs.androidx.fragment)
    implementation(libs.mediapipe.genai)
    implementation("com.k2fsa:sherpa-onnx:2.0.1@aar")
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}

dependencies {
    // Compose UI tests on the JVM (Robolectric): no emulator needed.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.kotlinx.coroutines.test)
}

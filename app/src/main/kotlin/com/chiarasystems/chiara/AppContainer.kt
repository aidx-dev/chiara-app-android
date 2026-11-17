package com.chiarasystems.chiara

import android.content.Context
import com.chiarasystems.chiara.data.AppointmentsStore
import com.chiarasystems.chiara.data.AuthStore
import com.chiarasystems.chiara.data.PatientsStore
import com.chiarasystems.chiara.data.ReportsStore
import com.chiarasystems.chiara.data.Router
import com.chiarasystems.chiara.data.SettingsStore
import com.chiarasystems.chiara.data.ToastCenter
import com.chiarasystems.chiara.designsystem.Translator
import com.chiarasystems.chiara.network.DemoBackend
import com.chiarasystems.chiara.network.FileResponseCache
import com.chiarasystems.chiara.network.SupabaseClient
import com.chiarasystems.chiara.network.SupabaseConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/** Manual dependency graph (the app is small enough that a DI framework would only add build time). */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsStore(SharedPreferencesStore(context))
    val secureStore = KeystoreSecureStore(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .apply { if (BuildConfig.DEMO_BACKEND) addInterceptor(DemoBackend()) }
        .build()

    // Cached RPC responses hold clinical data: encrypted, outside backups (noBackupFilesDir), wiped on sign-out.
    private val cache = FileResponseCache(File(context.noBackupFilesDir, "ResponseCache"), AesGcmCodec("chiara.cache"))

    val client = SupabaseClient(
        SupabaseConfig(if (BuildConfig.DEMO_BACKEND) "http://demo.invalid" else BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY),
        secureStore, cache, http,
    )

    val auth = AuthStore(client, secureStore)
    val patients = PatientsStore(client, appScope)
    val appointments = AppointmentsStore(client)
    val reports = ReportsStore(client)
    val router = Router()
    val toast = ToastCenter(appScope)

    private val assets = context.assets
    private val tables: Map<String, Map<String, String>> = listOf("es", "en", "de", "fr").associateWith { code ->
        runCatching {
            Json.parseToJsonElement(assets.open("i18n/$code.json").bufferedReader().use { it.readText() }).jsonObject
                .mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    fun translator(code: String) = Translator(code, tables[code].orEmpty())

    /** Nothing clinical may outlive the session in memory. */
    fun clearSessionState() {
        patients.reset(); appointments.reset(); reports.reset(); router.reset()
    }
}

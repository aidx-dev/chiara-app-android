package com.chiarasystems.chiara

import android.content.Context
import com.chiarasystems.chiara.data.AppointmentsStore
import com.chiarasystems.chiara.data.AssistantStore
import com.chiarasystems.chiara.data.ClinicReader
import com.chiarasystems.chiara.data.LlmIntentEngine
import com.chiarasystems.chiara.data.LlmPatientSummarizer
import com.chiarasystems.chiara.data.ToolCallingAgent
import com.chiarasystems.chiara.domain.FallbackEngine
import com.chiarasystems.chiara.ai.LocalModels
import com.chiarasystems.chiara.ai.MediaPipeLlm
import com.chiarasystems.chiara.ai.SherpaAsr
import com.chiarasystems.chiara.data.DayFormatter
import com.chiarasystems.chiara.domain.AssistantContext
import com.chiarasystems.chiara.domain.RuleBasedEngine
import com.chiarasystems.chiara.recording.DictationService
import java.util.Locale
import com.chiarasystems.chiara.data.AuthStore
import com.chiarasystems.chiara.data.PatientsStore
import com.chiarasystems.chiara.data.ReportsStore
import com.chiarasystems.chiara.data.Router
import com.chiarasystems.chiara.data.SettingsStore
import com.chiarasystems.chiara.data.ToastCenter
import com.chiarasystems.chiara.ai.CloudTranscriber
import com.chiarasystems.chiara.ai.ConsultationPipeline
import com.chiarasystems.chiara.ai.OpenRouterChat
import com.chiarasystems.chiara.ai.RecordingStore
import com.chiarasystems.chiara.ai.UserAIConfig
import com.chiarasystems.chiara.ai.WorkflowSteps
import com.chiarasystems.chiara.ai.Translate
import com.chiarasystems.chiara.designsystem.Translator
import com.chiarasystems.chiara.recording.AndroidPipelineBackground
import com.chiarasystems.chiara.recording.AppTranscribers
import com.chiarasystems.chiara.recording.ConsultationRecorder
import com.chiarasystems.chiara.recording.NoLocalAsr
import com.chiarasystems.chiara.recording.Notifier
import com.chiarasystems.chiara.recording.SqliteRecordingStore
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
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs = SharedPreferencesStore(context)
    val settings = SettingsStore(prefs)
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

    // --- Recording and processing (Phase 5)
    /** Number of started activities: the app is in front while it is above zero. */
    @Volatile var startedActivities = 0
    val isInFront: Boolean get() = startedActivities > 0

    val recordings: RecordingStore = SqliteRecordingStore(context)
    val recorder = ConsultationRecorder(context)
    /** The recording being captured now (survives the sheet being closed or recreated). */
    @Volatile var activeRecordingId: String? = null
    /** The user's AI settings from the server; read on demand and kept in memory only (the key never reaches the disk). */
    suspend fun aiConfig(): UserAIConfig = UserAIConfig.load(client)
    val chat = OpenRouterChat(config = ::aiConfig)
    val cloudTranscriber = CloudTranscriber(key = { aiConfig().apiKey })
    val models = LocalModels(context)
    private val localAsr = SherpaAsr(models)
    val transcribers = AppTranscribers(context, cloudTranscriber) { localAsr }
    private val translate = Translate { key, args -> translator(settings.code).tr(key, *args.toTypedArray()) }
    val pipeline = ConsultationPipeline(
        recordings, WorkflowSteps(recordings, transcribers, chat, translate) { settings.code }, appScope,
        isEnabled = { prefs.getString(AUTO_PROCESS_KEY) != "false" },
        notify = { if (!isInFront) Notifier.post(context, it) },
        prepare = {},
        background = AndroidPipelineBackground(context),
        isInFront = { isInFront },
    )

    // --- Assistant (Phase 6): the keyword rules answer commands; the on-device model/agent plugs in as `agent`.
    private val dayFormatter = object : DayFormatter {
        override fun dayChip(day: java.time.LocalDate) = calFormat(settings.code).dayChip(day)
        override fun longDay(day: java.time.LocalDate) = calFormat(settings.code).longDay(day)
    }
    private fun tr(key: String) = translator(settings.code).tr(key)
    /** The on-device language model (MediaPipe); the rules answer commands while it is not installed. */
    val llm = MediaPipeLlm(context, models, ::tr)
    val assistantReader = ClinicReader(patients, appointments, reports, dayFormatter, ::tr)
    val summarizer = LlmPatientSummarizer(llm, ClinicReader(patients, appointments, reports, dayFormatter, ::tr))
    val assistant = AssistantStore(
        engine = FallbackEngine(LlmIntentEngine(llm), RuleBasedEngine()),
        agent = ToolCallingAgent(llm, assistantReader, dayFormatter), patients = patients, appointments = appointments, days = dayFormatter,
        context = { AssistantContext(java.time.LocalDate.now(), settings.code, Locale.forLanguageTag(settings.code)) },
        tr = { key, args -> translator(settings.code).tr(key, *args.toTypedArray()) },
    )
    fun newDictation() = DictationService(context)

    private val assets = context.assets
    private val tables: Map<String, Map<String, String>> = listOf("es", "en", "de", "fr").associateWith { code ->
        runCatching {
            Json.parseToJsonElement(assets.open("i18n/$code.json").bufferedReader().use { it.readText() }).jsonObject
                .mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    companion object { const val AUTO_PROCESS_KEY = "recording.autoProcess" }

    fun translator(code: String) = Translator(code, tables[code].orEmpty())
    fun calFormat(code: String) = com.chiarasystems.chiara.designsystem.CalFormat(code)

    /** Nothing clinical may outlive the session in memory. */
    fun clearSessionState() {
        patients.reset(); appointments.reset(); reports.reset(); router.reset(); recordings.setOwner(null); assistant.reset()
    }
}

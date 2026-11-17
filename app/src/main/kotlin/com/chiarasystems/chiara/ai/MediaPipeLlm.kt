package com.chiarasystems.chiara.ai

import android.content.Context
import com.chiarasystems.chiara.data.AssistantAgentError
import com.chiarasystems.chiara.data.ChatMessage
import com.chiarasystems.chiara.data.ChatTemplate
import com.chiarasystems.chiara.data.LlmBackend
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The on-device language model through MediaPipe LLM Inference (the Android counterpart of iOS's `SystemLanguageModel`).
 * Greedy decoding (top-k 1, temperature 0), one request at a time. What it reads (patient names, reports) never leaves the
 * phone. The model is loaded lazily and kept while the app lives.
 */
class MediaPipeLlm(
    private val context: Context,
    private val models: LocalModels,
    private val tr: (String) -> String,
    private val template: (List<ChatMessage>) -> String = ChatTemplate::gemma,
) : LlmBackend {
    private var engine: LlmInference? = null
    private val lock = Mutex()

    override val unavailableReason: String?
        get() = if (models.llmInstalled.value) null
        else tr("Instala el modelo de IA del dispositivo en Ajustes para preguntar por pacientes e informes.")

    private fun load(): LlmInference = engine ?: LlmInference.createFromOptions(
        context,
        LlmInference.LlmInferenceOptions.builder().setModelPath(models.llmFile.path).setMaxTokens(2048).build(),
    ).also { engine = it }

    /** Loads the model ahead of the first question so it answers sooner. */
    override fun prepare() {
        if (unavailableReason != null) return
        Thread { runCatching { load() } }.apply { isDaemon = true }.start()
    }

    fun release() { engine?.close(); engine = null }

    override suspend fun complete(messages: List<ChatMessage>, maxTokens: Int, onPartial: (String) -> Unit): String = lock.withLock {
        if (unavailableReason != null) throw AssistantAgentError(AssistantAgentError.Kind.Failed, "model not installed")
        val prompt = template(messages)
        val llm = withContext(Dispatchers.IO) { load() }
        withContext(Dispatchers.Default) {
            val session = LlmInferenceSession.createFromOptions(
                llm,
                LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).setRandomSeed(1).build(),
            )
            try {
                session.addQueryChunk(prompt)
                suspendCancellableCoroutine { cont ->
                    val text = StringBuilder()
                    val future = session.generateResponseAsync { partial, done ->
                        text.append(partial)
                        onPartial(text.toString())
                        if (done && cont.isActive) cont.resume(text.toString())
                    }
                    cont.invokeOnCancellation { runCatching { session.cancelGenerateResponseAsync() } }
                    future.addListener({ runCatching { future.get() }.onFailure { if (cont.isActive) cont.resumeWithException(it) } }, java.util.concurrent.Executor { r -> r.run() })
                }
            } finally { session.close() }
        }
    }
}

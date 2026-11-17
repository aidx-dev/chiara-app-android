package com.chiarasystems.chiara.ai

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The on-device model files, kept in the app's private storage (never backed up). The language model (a MediaPipe `.task`
 * or `.litertlm` bundle, e.g. Gemma) is chosen by the person from their own files: models carry their own licences and are
 * hundreds of megabytes, so the app neither bundles nor downloads one on its own.
 */
class LocalModels(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    val llmFile: File get() = File(dir, "llm.task")

    private val _llmInstalled = MutableStateFlow(llmFile.exists() && llmFile.length() > 0)
    val llmInstalled: StateFlow<Boolean> = _llmInstalled.asStateFlow()

    fun llmSizeMb(): Long = llmFile.length() / (1024 * 1024)

    /** Copies the chosen file into private storage. Returns false when it cannot be read. */
    fun importLlm(uri: Uri): Boolean {
        return try {
            val tmp = File(dir, "llm.task.tmp")
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            if (!tmp.renameTo(llmFile)) { llmFile.delete(); tmp.renameTo(llmFile) }
            _llmInstalled.value = llmFile.exists() && llmFile.length() > 0
            _llmInstalled.value
        } catch (e: Exception) { false }
    }

    fun deleteLlm() { llmFile.delete(); _llmInstalled.value = false }
}

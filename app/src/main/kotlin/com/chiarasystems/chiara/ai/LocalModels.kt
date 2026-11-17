package com.chiarasystems.chiara.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

    // --- Speech recognition (sherpa-onnx, NeMo transducer such as Parakeet TDT 0.6B v3 int8)

    private val asrDir = File(dir, "asr").apply { mkdirs() }
    val asrEncoder: File get() = File(asrDir, "encoder.onnx")
    val asrDecoder: File get() = File(asrDir, "decoder.onnx")
    val asrJoiner: File get() = File(asrDir, "joiner.onnx")
    val asrTokens: File get() = File(asrDir, "tokens.txt")

    private fun asrComplete() = listOf(asrEncoder, asrDecoder, asrJoiner, asrTokens).all { it.exists() && it.length() > 0 }
    private val _asrInstalled = MutableStateFlow(asrComplete())
    val asrInstalled: StateFlow<Boolean> = _asrInstalled.asStateFlow()

    fun asrSizeMb(): Long = asrDir.listFiles().orEmpty().sumOf { it.length() } / (1024 * 1024)

    /**
     * Copies the files of the model folder the person picked (a SAF tree): the encoder, decoder and joiner `.onnx` files
     * (int8 or not, matched by name) and `tokens.txt`. Returns false when one is missing.
     */
    fun importAsrFolder(tree: Uri): Boolean {
        return try {
            val parent = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val found = mutableMapOf<String, Uri>()
            context.contentResolver.query(parent, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).lowercase()
                    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    when {
                        name == "tokens.txt" -> found["tokens"] = doc
                        name.endsWith(".onnx") && "encoder" in name -> found["encoder"] = preferInt8(found["encoder"], doc, name)
                        name.endsWith(".onnx") && "decoder" in name -> found["decoder"] = preferInt8(found["decoder"], doc, name)
                        name.endsWith(".onnx") && "joiner" in name -> found["joiner"] = preferInt8(found["joiner"], doc, name)
                    }
                }
            }
            val targets = mapOf("encoder" to asrEncoder, "decoder" to asrDecoder, "joiner" to asrJoiner, "tokens" to asrTokens)
            if (!targets.keys.all { it in found }) return false
            for ((key, target) in targets) {
                val tmp = File(asrDir, target.name + ".tmp")
                val input = context.contentResolver.openInputStream(found.getValue(key)) ?: return false
                input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            }
            _asrInstalled.value = asrComplete()
            _asrInstalled.value
        } catch (e: Exception) { false }
    }

    private val int8Names = mutableSetOf<Uri>()
    /** Both an int8 and a float file may be in the folder: the int8 one is smaller and faster on a phone. */
    private fun preferInt8(current: Uri?, candidate: Uri, name: String): Uri {
        if ("int8" in name) int8Names += candidate
        return if (current == null || (candidate in int8Names && current !in int8Names)) candidate else current
    }

    fun deleteAsr() { asrDir.listFiles()?.forEach { it.delete() }; _asrInstalled.value = false }
}

package com.chiarasystems.chiara.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dictation for the assistant (port of iOS `DictationService`): the platform recogniser, preferably the on-device one, with
 * the patients' names offered as a vocabulary (API 33+) so "Lucía Ferrer" is not heard as something else. Partial results
 * stream into [transcript]. Must be used from the main thread.
 */
class DictationService(private val context: Context) {
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing.asStateFlow()
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Names and phrases to favour (see `DictationVocabulary`). */
    var vocabulary: List<String> = emptyList()

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String) {
        if (_isListening.value || _isPreparing.value) return
        if (!isAvailable) { _error.value = "unavailable"; return }
        _error.value = null; _transcript.value = ""; _isPreparing.value = true
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _isPreparing.value = false; _isListening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) { take(partialResults) }
            override fun onResults(results: Bundle?) { take(results); finish() }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                // "No match" and "speech timeout" are just silence: not an error for the person.
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) _error.value = "error $error"
                finish()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= 33 && vocabulary.isNotEmpty()) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(vocabulary.take(300)))
        }
        r.startListening(intent)
    }

    private fun take(bundle: Bundle?) {
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _transcript.value = it }
    }

    private fun finish() {
        _isListening.value = false; _isPreparing.value = false
        recognizer?.destroy(); recognizer = null
    }

    /** Stops listening and keeps what was heard. */
    fun stop() { recognizer?.stopListening() }

    /** Stops and forgets. */
    fun cancel() { recognizer?.cancel(); finish(); _transcript.value = "" }
}

package com.chiarasystems.chiara.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PipelineEvent {
    Finished, Failed,
    /** A report asked for is written. */
    ReportReady,
    /** A step could not be done with the app in the background; it is done when the app is opened. */
    Deferred,
}

/** The steps of the automatic processing; an interface so the order, fallbacks and retries can be tested without the models. */
interface PipelineSteps {
    /** The transcript of the recording's audio; a message if there is none. */
    suspend fun transcribe(recording: Recording, progress: (Double) -> Unit): String?
    /** The live transcript written again from the audio by MAI-Transcribe 2 in the cloud. */
    suspend fun review(recording: Recording, progress: (Double) -> Unit): ReviewOutcome
    /** The report asked for, written from the transcript. [line] tells where it is ("Redactando… (3 de 7)"). */
    suspend fun writeReport(recording: Recording, progress: (Double) -> Unit, line: (String) -> Unit): ReportOutcome
}

/** The real steps, built on [RecordingWorkflow]. */
class WorkflowSteps(
    private val store: RecordingStore, private val transcribers: Transcribers, private val chat: OpenRouterChat,
    private val translate: Translate, private val appLanguage: () -> String,
) : PipelineSteps {
    private fun workflow(r: Recording) = RecordingWorkflow(r, store, transcribers, chat, translate, appLanguage)

    override suspend fun transcribe(recording: Recording, progress: (Double) -> Unit): String? {
        val w = workflow(recording).also { it.onProgress = progress }
        w.transcribeFromAudio()
        return if (recording.segments.isEmpty()) (w.problem ?: translate.tr("No se ha reconocido ninguna palabra en el audio.", emptyList())) else null
    }

    override suspend fun review(recording: Recording, progress: (Double) -> Unit): ReviewOutcome =
        workflow(recording).also { it.onProgress = progress }.reviewWithCloud()

    override suspend fun writeReport(recording: Recording, progress: (Double) -> Unit, line: (String) -> Unit): ReportOutcome =
        workflow(recording).also { it.onProgress = progress }.generatePendingReport(line)
}

/** What keeps the work going while the app is not in front (a foreground service with a notification on Android). */
interface PipelineBackground {
    fun begin()
    fun update(fraction: Double, subtitle: String)
    fun end(success: Boolean)

    object None : PipelineBackground {
        override fun begin() {}
        override fun update(fraction: Double, subtitle: String) {}
        override fun end(success: Boolean) {}
    }
}

/**
 * Transcribes, by itself, a consultation after it is recorded or imported. One consultation at a time, in order. Each
 * recording keeps its stage, so unfinished work resumes when the app opens again. A step that fails marks the recording and
 * is retried a few times, never turned into a question.
 */
class ConsultationPipeline(
    private val store: RecordingStore,
    private val steps: PipelineSteps,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean = { true },
    private val notify: (PipelineEvent) -> Unit = {},
    private val prepare: () -> Unit = {},
    private val background: PipelineBackground = PipelineBackground.None,
    /** Whether the app is in front. A step that fails with it in the background is not counted as a failure. */
    private val isInFront: () -> Boolean = { true },
    private val heartbeatIntervalMillis: Long = 10_000,
) {
    /** The recording being processed now. */
    private val _processingId = MutableStateFlow<String?>(null)
    val processingId: StateFlow<String?> = _processingId.asStateFlow()
    /** What the step under way says about where it is ("Redactando el informe… (3 de 7)"). */
    private val _processingLine = MutableStateFlow<String?>(null)
    val processingLine: StateFlow<String?> = _processingLine.asStateFlow()

    private var runDeferred = false
    private var currentStage: PipelineStage? = null
    private var currentWithin = 0.0
    private var plan: Map<PipelineStage, Pair<Double, Double>> = emptyMap()
    private var heartbeat: Job? = null
    private var runTotal = 0
    private var runFinished = 0
    private var runFailed = 0
    private var lastFraction = 0.0
    private val queue = ArrayDeque<String>()
    private var worker: Job? = null

    val isIdle: Boolean get() = worker == null

    /** Lets a test (or a caller) wait for everything queued so far. */
    suspend fun waitUntilIdle() { worker?.join() }

    // --- Queue

    /** Puts a recording in line. Does nothing when automatic processing is off, unless the person asked for it ([asked]: a report). */
    fun enqueue(recording: Recording, asked: Boolean = false) {
        if (!asked && !isEnabled()) return
        if (_processingId.value == recording.id || recording.id in queue) return
        recording.pipelineStage = PipelineStage.Queued
        recording.pipelineMessage = null
        store.save(recording)
        queue.addLast(recording.id)
        runTotal++
        prepare()
        startWorker()
    }

    /** Picks up what was left unfinished: work running when the app closed, a failure with attempts left, audio-only recordings. */
    fun resumeUnfinished() {
        for (r in store.recordings.value.toList()) {
            if (!needsProcessing(r)) continue
            // A report asked for by the person goes on even with automatic processing off.
            if (r.reportPending) enqueue(r, asked = true) else if (isEnabled()) enqueue(r)
        }
    }

    /** Writes the recording's report in the queue, in the background like the transcript. */
    fun requestReport(recording: Recording, template: ReportTemplate, language: String, patientSex: String?) {
        recording.reportTemplateId = template.id
        recording.reportLanguage = language
        recording.reportPatientSex = patientSex
        recording.reportProblem = null
        recording.reportPending = true
        // Asked again by the person: the attempts start over.
        if (recording.pipelineStage == PipelineStage.Failed) recording.pipelineAttempts = 0
        store.save(recording)
        enqueue(recording, asked = true)
    }

    private fun needsProcessing(r: Recording): Boolean = when (r.pipelineStage) {
        PipelineStage.Queued, PipelineStage.Transcribing, PipelineStage.Reviewing, PipelineStage.WritingReport -> true
        PipelineStage.Failed -> r.pipelineAttempts < MAX_ATTEMPTS
        PipelineStage.Done -> r.reportPending
        null -> r.status == RecordingStatus.Interrupted || r.status == RecordingStatus.Imported || r.cloudReviewPending || r.reportPending
    }

    private fun startWorker() {
        if (worker != null) return
        // The system is asked to keep going while the screen is locked, here and now (the app is in front).
        lastFraction = 0.0
        background.begin()
        heartbeat = scope.launch {
            while (isActive) { delay(heartbeatIntervalMillis); beat() }
        }
        worker = scope.launch {
            try {
                while (!runDeferred) {
                    val id = queue.firstOrNull() ?: break
                    queue.removeFirst()
                    val r = store.recording(id)
                    if (r != null) process(r) else runTotal = maxOf(runTotal - 1, 0) // not visible now: stays queued on disk
                }
            } finally {
                heartbeat?.cancel(); heartbeat = null
                currentStage = null
                background.end(runFailed == 0 && !runDeferred)
                // What was left waits, queued on disk, until the app is opened (`resumeUnfinished`).
                queue.clear()
                runDeferred = false; runTotal = 0; runFinished = 0; runFailed = 0
                _processingId.value = null; _processingLine.value = null
                worker = null
            }
        }
    }

    // --- One recording

    private suspend fun process(r: Recording) {
        _processingId.value = r.id
        plan = plan(r, store.hasAudio(r))
        try {
            // A recording cut short has a partial live transcript: the saved audio is the whole of it.
            if (r.status == RecordingStatus.Interrupted && store.hasAudio(r)) r.segments = emptyList()

            if (r.segments.isEmpty()) {
                if (!store.hasAudio(r)) { fail(r, "El audio de esta consulta ya no está.", retry = false); return }
                set(r, PipelineStage.Transcribing)
                steps.transcribe(r) { report(PipelineStage.Transcribing, it) }?.let { fail(r, it); return }
            }

            // The live transcript is the fast one; the one kept is MAI-Transcribe's, written from the whole audio. Until it
            // is, the live one stays: a failure keeps it and tries again, like any other step.
            if (r.cloudReviewPending) {
                if (store.hasAudio(r)) {
                    set(r, PipelineStage.Reviewing)
                    when (val o = steps.review(r) { report(PipelineStage.Reviewing, it) }) {
                        ReviewOutcome.Done, ReviewOutcome.Unavailable -> r.cloudReviewPending = false
                        is ReviewOutcome.Failed -> { fail(r, o.message); return }
                    }
                } else r.cloudReviewPending = false
            }

            // The report asked for, once the transcript is the one to keep.
            var wroteReport = false
            if (r.reportPending) {
                set(r, PipelineStage.WritingReport)
                when (val o = steps.writeReport(r, { report(PipelineStage.WritingReport, it) }, { _processingLine.value = it })) {
                    ReportOutcome.Done -> { r.reportPending = false; r.reportProblem = null; wroteReport = true }
                    // Nothing to retry; the transcript is fine, so the recording is done.
                    is ReportOutcome.Refused -> { r.reportPending = false; r.reportProblem = o.message }
                    is ReportOutcome.Failed -> { r.reportProblem = o.message; fail(r, o.message); return }
                }
            }

            r.pipelineMessage = null
            set(r, PipelineStage.Done)
            notify(if (wroteReport) PipelineEvent.ReportReady else PipelineEvent.Finished)
        } finally {
            runFinished++
        }
    }

    /** The steps a recording goes through in this run and their share of its progress: the cloud ones take longest. */
    private fun plan(r: Recording, hasAudio: Boolean): Map<PipelineStage, Pair<Double, Double>> {
        val list = mutableListOf<Pair<PipelineStage, Double>>()
        if (r.segments.isEmpty() || (r.status == RecordingStatus.Interrupted && hasAudio)) list += PipelineStage.Transcribing to 1.0
        if (r.cloudReviewPending) list += PipelineStage.Reviewing to 2.0
        if (r.reportPending) list += PipelineStage.WritingReport to 3.0
        val total = list.sumOf { it.second }
        var base = 0.0
        val out = LinkedHashMap<PipelineStage, Pair<Double, Double>>()
        for ((stage, w) in list) { out[stage] = base / maxOf(total, 1.0) to w / maxOf(total, 1.0); base += w }
        return out
    }

    private fun set(r: Recording, stage: PipelineStage) {
        r.pipelineStage = stage
        _processingLine.value = null
        store.save(r)
        report(stage, 0.0)
    }

    /** Where each stage sits inside one recording's share of the run. */
    private fun report(stage: PipelineStage, within: Double) {
        if (stage != currentStage) { currentStage = stage; currentWithin = 0.0 }
        currentWithin = maxOf(currentWithin, within.coerceIn(0.0, 1.0))
        val (base, span) = plan[stage] ?: (0.0 to 0.0)
        val fraction = (runFinished + base + span * currentWithin) / maxOf(runTotal, 1)
        lastFraction = maxOf(lastFraction, minOf(fraction, 0.999))
        background.update(lastFraction, _processingLine.value ?: stage.statusLineKey(null).orEmpty())
    }

    /** Moves the progress a little while a step says nothing of its own (downloading a model), so the system keeps the task. */
    private fun beat() {
        val stage = currentStage ?: return
        report(stage, currentWithin + (0.95 - currentWithin) * 0.03)
    }

    private fun fail(r: Recording, message: String, retry: Boolean = true) {
        // In the background the system may hold back the models: the step is done again when the app is opened, and it is
        // not counted as one of the attempts.
        if (retry && !isInFront()) {
            r.pipelineStage = PipelineStage.Queued
            r.pipelineMessage = "Se terminará al abrir la app."
            runDeferred = true
            store.save(r)
            notify(PipelineEvent.Deferred)
            return
        }
        r.pipelineStage = PipelineStage.Failed
        r.pipelineMessage = message
        r.pipelineAttempts = if (retry) r.pipelineAttempts + 1 else MAX_ATTEMPTS
        runFailed++
        store.save(r)
        notify(PipelineEvent.Failed)
    }

    companion object {
        /** Failures after which it stops retrying on its own (the person can still reprocess from the recording). */
        const val MAX_ATTEMPTS = 3
    }
}

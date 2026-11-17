package com.chiarasystems.chiara.ai

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted steps: what ran, in which order, and how it ended, without the speech models. */
private class StubSteps : PipelineSteps {
    val calls = mutableListOf<String>()
    var transcribeProblem: String? = null
    var segmentsOnTranscribe = listOf(TranscriptSegment(0.0, 2.0, "Hola, doctor."))
    val segmentsSeenAtTranscribe = mutableListOf<Int>()
    var transcribePause: Long? = null
    private var running = 0
    var mostAtOnce = 0

    override suspend fun transcribe(recording: Recording, progress: (Double) -> Unit): String? {
        calls += "transcribe"
        segmentsSeenAtTranscribe += recording.segments.size
        transcribePause?.let { delay(it) }
        running++; mostAtOnce = maxOf(mostAtOnce, running)
        yield()
        running--
        progress(0.5)
        transcribeProblem?.let { return it }
        recording.segments = segmentsOnTranscribe
        recording.status = RecordingStatus.Transcribed
        return null
    }

    var reviewOutcome: ReviewOutcome = ReviewOutcome.Done
    var segmentsOnReview = listOf(TranscriptSegment(0.0, 2.0, "Hola, doctora."))

    override suspend fun review(recording: Recording, progress: (Double) -> Unit): ReviewOutcome {
        calls += "review"
        yield(); progress(0.5)
        if (reviewOutcome == ReviewOutcome.Done) { recording.segments = segmentsOnReview; recording.transcriptionModel = TranscriptionModel.Mai }
        return reviewOutcome
    }

    var reportOutcome: ReportOutcome = ReportOutcome.Done
    var reportAskedWith: Triple<String?, String?, String?>? = null

    override suspend fun writeReport(recording: Recording, progress: (Double) -> Unit, line: (String) -> Unit): ReportOutcome {
        calls += "report"
        reportAskedWith = Triple(recording.reportTemplateId, recording.reportLanguage, recording.reportPatientSex)
        line("Redactando el informe… (1 de 1)")
        yield(); progress(0.5)
        if (reportOutcome == ReportOutcome.Done) {
            recording.report = GeneratedReport(
                recording.reportTemplateId ?: "vascular", 1, recording.reportLanguage ?: "Español", 0, listOf("m"),
                listOf(GeneratedReport.Section("anamnesis", "Anamnesis", "Dolor.")),
            )
        }
        return reportOutcome
    }
}

private class SpyBackground : PipelineBackground {
    var begins = 0
    val updates = mutableListOf<Double>()
    val subtitles = mutableListOf<String>()
    val ends = mutableListOf<Boolean>()
    override fun begin() { begins++ }
    override fun update(fraction: Double, subtitle: String) { updates += fraction; subtitles += subtitle }
    override fun end(success: Boolean) { ends += success }
}

class ConsultationPipelineTest {
    private val dir = File.createTempFile("recs", "").also { it.delete(); it.mkdirs() }
    @After fun cleanup() { dir.deleteRecursively() }

    private inner class Fixture(scope: CoroutineScope, enabled: Boolean, inFront: Boolean, heartbeat: Long) {
        val store = InMemoryRecordingStore(dir).also { it.setOwner("ana") }
        val steps = StubSteps()
        val events = mutableListOf<PipelineEvent>()
        val background = SpyBackground()
        val pipeline = ConsultationPipeline(store, steps, scope, { enabled }, { events += it }, {}, background, { inFront }, heartbeat)

        fun recording(audio: Boolean = true, transcript: Boolean = false, status: RecordingStatus = RecordingStatus.Transcribed): Recording {
            val r = store.create(null, "Paciente")
            if (audio) store.audioFile(r)!!.writeBytes(byteArrayOf(1))
            if (transcript) r.segments = listOf(TranscriptSegment(0.0, 1.0, "Ya transcrito."))
            r.status = status
            return r
        }

        /** A recording made with the app: the live transcript, to be reviewed by MAI-Transcribe. */
        fun recorded(): Recording = recording(transcript = true).also { it.cloudReviewPending = true }
    }

    private fun test(enabled: Boolean = true, inFront: Boolean = true, heartbeat: Long = 10_000, body: suspend TestScope.(Fixture) -> Unit) = runTest {
        body(Fixture(backgroundScope, enabled, inFront, heartbeat))
    }

    private fun texts(r: Recording) = r.segments.map { it.text }
    private fun List<Double>.forwardOnly() = zip(drop(1)).all { (a, b) -> a <= b }

    @Test fun anImportedRecordingIsTranscribedAndEndsDone() = test { f ->
        val r = f.recording(status = RecordingStatus.Imported)
        f.pipeline.enqueue(r)
        assertEquals(PipelineStage.Queued, r.pipelineStage)
        f.pipeline.waitUntilIdle()
        assertEquals(listOf("transcribe"), f.steps.calls)
        assertTrue(r.pipelineStage == PipelineStage.Done && r.pipelineMessage == null)
        assertEquals(listOf("Hola, doctor."), texts(r))
        assertEquals(listOf(PipelineEvent.Finished), f.events)
        assertTrue(f.pipeline.isIdle && f.pipeline.processingId.value == null)
    }

    @Test fun aLiveTranscriptIsKeptAsItIs() = test { f ->
        val r = f.recording(transcript = true)
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertTrue(f.steps.calls.isEmpty())
        assertTrue(texts(r) == listOf("Ya transcrito.") && r.pipelineStage == PipelineStage.Done)
    }

    @Test fun aFailedTranscriptionIsMarked() = test { f ->
        f.steps.transcribeProblem = "Sin voz"
        val r = f.recording(status = RecordingStatus.Imported)
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertEquals(listOf("transcribe"), f.steps.calls)
        assertTrue(r.pipelineStage == PipelineStage.Failed && r.pipelineMessage == "Sin voz" && r.pipelineAttempts == 1)
        assertEquals(listOf(PipelineEvent.Failed), f.events)
    }

    @Test fun aFailureInTheBackgroundWaitsForTheAppWithoutCountingAsAnAttempt() = test(inFront = false) { f ->
        f.steps.transcribeProblem = "Sin modelo"
        val r = f.recording(status = RecordingStatus.Imported)
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertTrue(r.pipelineStage == PipelineStage.Queued && r.pipelineAttempts == 0)
        assertEquals(listOf(PipelineEvent.Deferred), f.events)
    }

    @Test fun aRecordingWithoutItsAudioIsNotRetried() = test { f ->
        val r = f.recording(audio = false, status = RecordingStatus.Imported)
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertTrue(f.steps.calls.isEmpty())
        assertTrue(r.pipelineStage == PipelineStage.Failed && r.pipelineAttempts == ConsultationPipeline.MAX_ATTEMPTS)
        f.pipeline.resumeUnfinished(); f.pipeline.waitUntilIdle() // and so it is not picked up again
        assertTrue(f.steps.calls.isEmpty())
    }

    @Test fun aRecordingCutShortIsTranscribedAgainFromItsAudio() = test { f ->
        val r = f.recording(transcript = true, status = RecordingStatus.Interrupted) // a partial live transcript
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertEquals(listOf(0), f.steps.segmentsSeenAtTranscribe)
        assertEquals(listOf("Hola, doctor."), texts(r))
    }

    @Test fun unfinishedWorkIsPickedUpAgainAndFinishedWorkIsNot() = test { f ->
        val running = f.recording(status = RecordingStatus.Imported).also { it.pipelineStage = PipelineStage.Transcribing }
        val retryable = f.recording(status = RecordingStatus.Imported).also { it.pipelineStage = PipelineStage.Failed; it.pipelineAttempts = 1 }
        val exhausted = f.recording(status = RecordingStatus.Imported).also { it.pipelineStage = PipelineStage.Failed; it.pipelineAttempts = ConsultationPipeline.MAX_ATTEMPTS }
        val done = f.recording(transcript = true).also { it.pipelineStage = PipelineStage.Done }
        val neverProcessed = f.recording(status = RecordingStatus.Imported)
        val plainFinished = f.recording(transcript = true, status = RecordingStatus.Transcribed)

        f.pipeline.resumeUnfinished(); f.pipeline.waitUntilIdle()

        assertTrue(listOf(running, retryable, neverProcessed).all { it.pipelineStage == PipelineStage.Done })
        assertTrue(exhausted.pipelineStage == PipelineStage.Failed && exhausted.pipelineAttempts == ConsultationPipeline.MAX_ATTEMPTS)
        assertEquals(PipelineStage.Done, done.pipelineStage)
        assertNull(plainFinished.pipelineStage)
    }

    @Test fun recordingsAreProcessedOneAtATimeInOrder() = test { f ->
        val rs = List(3) { f.recording(status = RecordingStatus.Imported) }
        rs.forEach { f.pipeline.enqueue(it) }
        f.pipeline.waitUntilIdle()
        assertEquals(1, f.steps.mostAtOnce)
        assertEquals(3, f.steps.calls.size)
        assertTrue(rs.all { it.pipelineStage == PipelineStage.Done })
        f.steps.calls.clear() // putting one in line twice does not process it twice
        val d = f.recording(status = RecordingStatus.Imported)
        f.pipeline.enqueue(d); f.pipeline.enqueue(d); f.pipeline.waitUntilIdle()
        assertEquals(listOf("transcribe"), f.steps.calls)
    }

    @Test fun nothingRunsWhenAutomaticProcessingIsOff() = test(enabled = false) { f ->
        val r = f.recording(status = RecordingStatus.Imported)
        f.pipeline.enqueue(r); f.pipeline.resumeUnfinished(); f.pipeline.waitUntilIdle()
        assertTrue(f.steps.calls.isEmpty() && r.pipelineStage == null)
    }

    // --- Review in the cloud

    @Test fun aRecordedConsultationIsReviewedInTheCloudAndKeepsItsTranscript() = test { f ->
        val r = f.recorded()
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertEquals(listOf("review"), f.steps.calls) // the live transcript is not transcribed again: straight to the review
        assertTrue(texts(r) == listOf("Hola, doctora.") && r.transcriptionModel == TranscriptionModel.Mai)
        assertTrue(!r.cloudReviewPending && r.pipelineStage == PipelineStage.Done)
        assertEquals(listOf(PipelineEvent.Finished), f.events)
        assertTrue("Revisando la transcripción…" in f.background.subtitles)
        assertTrue(f.background.updates.forwardOnly())
    }

    @Test fun withoutCloudAIForTheAccountTheLiveTranscriptStays() = test { f ->
        f.steps.reviewOutcome = ReviewOutcome.Unavailable
        val r = f.recorded()
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertEquals(listOf("Ya transcrito."), texts(r))
        assertTrue(!r.cloudReviewPending && r.pipelineStage == PipelineStage.Done && f.events == listOf(PipelineEvent.Finished))
    }

    @Test fun aFailedReviewKeepsTheLiveTranscriptAndIsTriedAgain() = test { f ->
        f.steps.reviewOutcome = ReviewOutcome.Failed("Sin conexión")
        val r = f.recorded()
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertTrue(texts(r) == listOf("Ya transcrito.") && r.cloudReviewPending)
        assertTrue(r.pipelineStage == PipelineStage.Failed && r.pipelineMessage == "Sin conexión" && r.pipelineAttempts == 1)
        f.steps.reviewOutcome = ReviewOutcome.Done // next time the app opens, it is reviewed again
        f.pipeline.resumeUnfinished(); f.pipeline.waitUntilIdle()
        assertTrue(texts(r) == listOf("Hola, doctora.") && r.pipelineStage == PipelineStage.Done && !r.cloudReviewPending)
    }

    @Test fun withTheScreenLockedAFailedReviewWaitsWithoutUsingAnAttempt() = test(inFront = false) { f ->
        f.steps.reviewOutcome = ReviewOutcome.Failed("Sin conexión")
        val r = f.recorded()
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertTrue(r.pipelineStage == PipelineStage.Queued && r.pipelineAttempts == 0 && r.cloudReviewPending)
        assertEquals(listOf(PipelineEvent.Deferred), f.events)
        assertTrue(f.background.begins == 1 && f.background.ends == listOf(false))
    }

    @Test fun aRecordingCutShortIsTranscribedFromItsAudioAndThenReviewed() = test { f ->
        val r = f.recording(transcript = true, status = RecordingStatus.Interrupted).also { it.cloudReviewPending = true }
        f.pipeline.enqueue(r); f.pipeline.waitUntilIdle()
        assertEquals(listOf("transcribe", "review"), f.steps.calls)
        assertTrue(texts(r) == listOf("Hola, doctora.") && r.pipelineStage == PipelineStage.Done)
    }

    @Test fun aReviewLeftPendingWhenTheAppClosedIsPickedUpAgain() = test { f ->
        val r = f.recorded() // never queued: the app was closed right after recording
        f.pipeline.resumeUnfinished(); f.pipeline.waitUntilIdle()
        assertTrue(f.steps.calls == listOf("review") && r.pipelineStage == PipelineStage.Done)
    }

    // --- Report in the background

    @Test fun aReportAskedForIsWrittenInTheQueueAndNotifiedAsReady() = test { f ->
        val r = f.recording(transcript = true).also { it.pipelineStage = PipelineStage.Done }
        f.pipeline.requestReport(r, ReportTemplates.resolve("psychology"), "English", "female")
        assertTrue(r.pipelineStage == PipelineStage.Queued && r.reportPending)
        f.pipeline.waitUntilIdle()
        assertEquals(listOf("report"), f.steps.calls)
        assertEquals(Triple<String?, String?, String?>("psychology", "English", "female"), f.steps.reportAskedWith)
        assertTrue(r.report?.sections?.first()?.text == "Dolor." && !r.reportPending && r.reportProblem == null)
        assertTrue(r.pipelineStage == PipelineStage.Done && f.events == listOf(PipelineEvent.ReportReady))
        assertTrue(f.background.begins == 1 && f.background.ends == listOf(true))
        assertTrue("Redactando el informe… (1 de 1)" in f.background.subtitles)
        assertNull(f.pipeline.processingLine.value)
    }

    @Test fun aReportAskedForWhileRecordingIsReviewedWaitsForTheReview() = test { f ->
        val r = f.recorded()
        f.pipeline.enqueue(r)
        f.pipeline.requestReport(r, ReportTemplates.resolve(null), "Español", null)
        f.pipeline.waitUntilIdle()
        assertEquals(listOf("review", "report"), f.steps.calls) // the report reads MAI's transcript, not the live one
        assertTrue(r.transcriptionModel == TranscriptionModel.Mai && r.report != null && r.pipelineStage == PipelineStage.Done)
        assertTrue(f.background.updates.forwardOnly())
    }

    @Test fun aReportThatCannotBeWrittenNowIsTriedAgainAndOneRefusedIsNot() = test { f ->
        val r = f.recording(transcript = true)
        f.steps.reportOutcome = ReportOutcome.Failed("Sin conexión")
        f.pipeline.requestReport(r, ReportTemplates.resolve(null), "Español", null); f.pipeline.waitUntilIdle()
        assertTrue(r.reportPending && r.reportProblem == "Sin conexión" && r.pipelineStage == PipelineStage.Failed)
        assertTrue(r.segments.isNotEmpty()) // the transcript is untouched
        f.steps.reportOutcome = ReportOutcome.Done
        f.pipeline.resumeUnfinished(); f.pipeline.waitUntilIdle()
        assertTrue(!r.reportPending && r.report != null && r.reportProblem == null && r.pipelineStage == PipelineStage.Done)

        val other = f.recording(transcript = true)
        f.steps.reportOutcome = ReportOutcome.Refused("Tu cuenta no tiene IA en la nube")
        f.pipeline.requestReport(other, ReportTemplates.resolve(null), "Español", null); f.pipeline.waitUntilIdle()
        assertTrue(!other.reportPending && other.reportProblem == "Tu cuenta no tiene IA en la nube" && other.pipelineStage == PipelineStage.Done)
    }

    @Test fun withTheScreenLockedAFailedReportWaitsForTheApp() = test(inFront = false) { f ->
        f.steps.reportOutcome = ReportOutcome.Failed("Sin conexión")
        val r = f.recording(transcript = true)
        f.pipeline.requestReport(r, ReportTemplates.resolve(null), "Español", null); f.pipeline.waitUntilIdle()
        assertTrue(r.pipelineStage == PipelineStage.Queued && r.pipelineAttempts == 0 && r.reportPending)
        assertEquals(listOf(PipelineEvent.Deferred), f.events)
    }

    @Test fun aReportIsWrittenEvenWithAutomaticProcessingOff() = test(enabled = false) { f ->
        val r = f.recording(transcript = true)
        f.pipeline.requestReport(r, ReportTemplates.resolve(null), "Español", null); f.pipeline.waitUntilIdle()
        assertTrue(f.steps.calls == listOf("report") && r.report != null)
        val pending = f.recording(transcript = true).also { it.reportPending = true } // left pending when the app closed
        f.pipeline.resumeUnfinished(); f.pipeline.waitUntilIdle()
        assertTrue(pending.report != null && !pending.reportPending)
    }

    @Test fun theStatusLinesAreForActiveAndFailedStagesOnly() {
        assertTrue(PipelineStage.WritingReport.statusLineKey(null) == "Redactando el informe…" && PipelineStage.WritingReport.isActive)
        assertTrue(PipelineStage.Reviewing.statusLineKey(null) == "Revisando la transcripción…" && PipelineStage.Reviewing.isActive)
        assertEquals("Transcribiendo…", PipelineStage.Transcribing.statusLineKey(null))
        assertEquals("Motivo", PipelineStage.Failed.statusLineKey("Motivo"))
        assertNull(PipelineStage.Done.statusLineKey(null))
        assertTrue(PipelineStage.Queued.isActive && !PipelineStage.Failed.isActive && !PipelineStage.Done.isActive)
    }

    // --- Background run

    @Test fun oneRunAsksTheSystemOnceAndReportsAForwardOnlyProgress() = test { f ->
        val a = f.recording(status = RecordingStatus.Imported)
        val b = f.recording(status = RecordingStatus.Imported)
        f.pipeline.enqueue(a)
        f.pipeline.enqueue(b) // while the first is waiting: still the same run
        f.pipeline.waitUntilIdle()
        assertTrue(f.background.begins == 1 && f.background.ends == listOf(true))
        assertTrue(f.background.updates.isNotEmpty() && f.background.updates.forwardOnly())
        assertTrue(f.background.updates.all { it in 0.0..1.0 })
        assertTrue("Transcribiendo…" in f.background.subtitles)
    }

    @Test fun aFailedRecordingEndsTheRunAsUnsuccessful() = test { f ->
        f.steps.transcribeProblem = "Sin voz"
        f.pipeline.enqueue(f.recording(status = RecordingStatus.Imported)); f.pipeline.waitUntilIdle()
        assertTrue(f.background.begins == 1 && f.background.ends == listOf(false))
    }

    @Test fun eachNewRunAsksAgainAndNothingQueuedAsksForNothing() = test { f ->
        f.pipeline.resumeUnfinished(); f.pipeline.waitUntilIdle()
        assertTrue(f.background.begins == 0 && f.background.ends.isEmpty())
        f.pipeline.enqueue(f.recording(status = RecordingStatus.Imported)); f.pipeline.waitUntilIdle()
        f.pipeline.enqueue(f.recording(status = RecordingStatus.Imported)); f.pipeline.waitUntilIdle()
        assertTrue(f.background.begins == 2 && f.background.ends == listOf(true, true))
    }

    @Test fun aLongStepThatSaysNothingStillMovesTheProgress() = test(heartbeat = 10) { f ->
        f.steps.transcribePause = 300 // a model being downloaded
        f.pipeline.enqueue(f.recording(status = RecordingStatus.Imported)); f.pipeline.waitUntilIdle()
        val transcribing = f.background.subtitles.zip(f.background.updates).filter { it.first == "Transcribiendo…" }.map { it.second }
        assertTrue(transcribing.size >= 5)
        assertTrue(transcribing.toSet().size >= 5)
    }

    // --- Store

    @Test fun eachUserOnlySeesTheirOwnAndSigningOutKeepsThem() = test { f ->
        val mine = f.store.create("p", "Ana")
        f.store.setOwner("luis")
        assertTrue(f.store.recordings.value.isEmpty())
        f.store.setOwner("ana")
        assertEquals(listOf(mine.id), f.store.recordings.value.map { it.id })
        f.store.setOwner(null)
        assertTrue(f.store.recordings.value.isEmpty())
    }

    @Test fun deleteAllRemovesOnlyTheSignedInUsersRowsAndAudio() = test { f ->
        val a = f.recording()
        f.store.setOwner("luis")
        val b = f.recording()
        f.store.deleteAll()
        assertFalse(f.store.audioFile(b)?.exists() ?: false)
        f.store.setOwner("ana")
        assertEquals(listOf(a.id), f.store.recordings.value.map { it.id })
        assertTrue(f.store.hasAudio(a))
    }
}

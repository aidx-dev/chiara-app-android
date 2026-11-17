package com.chiarasystems.chiara.ai

import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cloud recogniser with OpenRouter stubbed: nothing here leaves the machine. */
class CloudTranscriberTest {
    /** A tone at 16 kHz, silent between [silence] seconds. */
    private class Tone(seconds: Double, silence: ClosedFloatingPointRange<Double>? = null) : PcmSource {
        override val sampleRate = 16_000
        private val data = ShortArray((seconds * sampleRate).toInt()) { n ->
            val t = n.toDouble() / sampleRate
            if (silence?.contains(t) == true) 0 else (0.4 * 32767 * sin(2 * PI * 440 * t)).toInt().toShort()
        }
        override val frames get() = data.size.toLong()
        override fun read(from: Long, count: Int) = data.copyOfRange(from.toInt(), minOf(data.size, from.toInt() + count))
    }

    private class Calls(val answers: List<Pair<Int, String>>) {
        val bodies = mutableListOf<JsonObject>()
        val keys = mutableListOf<String>()
        val sender = HttpSender { _, key, body, _ ->
            keys += key
            bodies += Json.parseToJsonElement(body).jsonObject
            val (status, json) = answers[minOf(bodies.size, answers.size) - 1]
            HttpReply(status, json.toByteArray())
        }
    }

    private fun transcriber(calls: Calls, key: String? = "sk-or-test") = CloudTranscriber(key = { key }, sender = calls.sender, retryDelay = {})

    @Test fun aRecordingThatFitsGoesWholeInOneRequestAsWavWithZeroRetention() = runTest {
        val calls = Calls(listOf(200 to """{"text":"Hola. Buenos días.","segments":[{"start":0.2,"end":1.1,"text":" Hola."},{"start":1.3,"end":2.4,"text":"Buenos días."}]}"""))
        val segments = transcriber(calls).transcribe(Tone(6.0), "es")
        assertEquals(listOf("Hola.", "Buenos días."), segments.map { it.text })
        assertEquals(listOf(0.2, 1.3), segments.map { it.start })
        assertEquals(1, calls.bodies.size)
        assertEquals("sk-or-test", calls.keys.single())
        val body = calls.bodies.single()
        assertEquals("microsoft/mai-transcribe-2", body["model"]!!.jsonPrimitive.content)
        assertEquals("es", body["language"]!!.jsonPrimitive.content)
        assertEquals("verbose_json", body["response_format"]!!.jsonPrimitive.content)
        val input = body["input_audio"] as JsonObject
        assertEquals("wav", input["format"]!!.jsonPrimitive.content)
        val wav = Base64.getDecoder().decode(input["data"]!!.jsonPrimitive.content)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(44 + 6 * 16_000 * 2, wav.size) // the whole recording, in one piece
        val provider = body["provider"] as JsonObject
        assertEquals("true", provider["zdr"]!!.jsonPrimitive.content)
        assertEquals("deny", provider["data_collection"]!!.jsonPrimitive.content)
    }

    @Test fun aTimeoutSendsTheStretchAgainInTwoHalvesCutInASilence() = runTest {
        val calls = Calls(listOf(
            504 to "{}",
            200 to """{"segments":[{"start":0.5,"end":1.0,"text":"Primera."}]}""",
            200 to """{"segments":[{"start":0.5,"end":1.0,"text":"Segunda."}]}""",
        ))
        val t = transcriber(calls).apply { attempts = 1; minSplitSeconds = 2.0 }
        val segments = t.transcribe(Tone(12.0, 5.5..6.5), "es")
        assertEquals(listOf("Primera.", "Segunda."), segments.map { it.text })
        val cut = segments[1].start - 0.5 // the second half starts where the first ends: inside the silence
        assertTrue("cut at $cut", cut in 5.4..6.6)
        assertEquals(0.5, segments[0].start, 0.0)
        assertEquals(3, calls.bodies.size)
    }

    @Test fun aRecordingTooBigForOneRequestGoesInStretchesCutInSilences() = runTest {
        val calls = Calls(listOf(200 to """{"segments":[{"start":0.5,"end":1.0,"text":"Uno."}]}"""))
        // As if 12 s were twice what one request takes.
        val t = transcriber(calls).apply { estimatedBytesPerSecond = 1_000.0; maxRequestBytes = 6_000; minSplitSeconds = 100.0 }
        // The WAV of a stretch is bigger than maxRequestBytes, but the stretches are too short to split again.
        val segments = t.transcribe(Tone(12.0, 5.5..6.5), "es")
        assertEquals(2, segments.size)
        assertTrue("second stretch at ${segments[1].start}", segments[1].start in 5.9..7.1)
        assertEquals(2, calls.bodies.size)
    }

    @Test fun speakersAreAskedForOnlyWhenWantedAndReadFromEachSegment() = runTest {
        val calls = Calls(listOf(200 to """{"segments":[{"start":0.1,"end":1.0,"text":"¿Qué le pasa?","speaker":0},{"start":1.2,"end":2.0,"text":"Me duele.","speaker":1},{"start":2.1,"end":3.0,"text":"Desde ayer.","speaker":"1"}]}"""))
        val t = transcriber(calls)
        val plain = t.transcribe(Tone(4.0), "es")
        assertNull((calls.bodies.first()["provider"] as JsonObject)["options"])
        assertEquals(3, plain.size)
        val withSpeakers = t.transcribe(Tone(4.0), "es", diarize = true)
        assertEquals(listOf<Int?>(0, 1, 1), withSpeakers.map { it.speaker })
        assertEquals(listOf("¿Qué le pasa?", "Me duele.", "Desde ayer."), withSpeakers.map { it.text })
        val options = (calls.bodies.last()["provider"] as JsonObject)["options"] as JsonObject
        assertEquals("true", ((options["azure"] as JsonObject)["diarization"] as JsonObject)["enabled"]!!.jsonPrimitive.content)
    }

    @Test fun transientAnswersAreRetriedAndAWrongKeyIsNot() = runTest {
        val busy = Calls(listOf(429 to "{}", 200 to """{"text":"Bien."}"""))
        val segments = transcriber(busy).transcribe(Tone(3.0), "es")
        assertEquals(listOf("Bien."), segments.map { it.text }) // no segments: its whole text over the whole stretch
        assertEquals(0.0, segments.first().start, 0.0)
        assertTrue(segments.first().end > 2.9)
        assertEquals(2, busy.bodies.size)

        val refused = Calls(listOf(401 to """{"error":{"message":"No auth"}}"""))
        val e = runCatching { transcriber(refused).transcribe(Tone(3.0), "es") }.exceptionOrNull()
        assertEquals(CloudTranscriberError.Rejected(401), e)
        assertEquals(1, refused.bodies.size)
    }

    @Test fun withoutAKeyNothingIsSent() = runTest {
        val calls = Calls(listOf(200 to "{}"))
        val e = runCatching { transcriber(calls, key = null).transcribe(Tone(2.0), "es") }.exceptionOrNull()
        assertEquals(CloudTranscriberError.NotAvailable, e)
        assertTrue(calls.bodies.isEmpty())
    }

    @Test fun theQuietestWindowIsFound() {
        val samples = ShortArray(1000) { 16_000 }
        for (i in 600 until 700) samples[i] = 0
        assertTrue(AudioChunker.quietest(samples, 100) in 600..700)
    }
}

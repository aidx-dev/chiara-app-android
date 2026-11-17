package com.chiarasystems.chiara.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenSegmenterTest {
    private val sp = "▁"

    @Test fun splitsAtSentenceEndsKeepingTheirTimesAndTheChunkOffset() {
        val tokens = listOf("${sp}Hola", ",", "${sp}doctor", ".", "${sp}Me", "${sp}duele", "${sp}la", "${sp}pierna", ".")
        val times = listOf(0.2f, 0.5f, 0.6f, 1.0f, 2.0f, 2.2f, 2.5f, 2.7f, 3.1f)
        val segments = TokenSegmenter.segments("Hola, doctor. Me duele la pierna.", tokens, times, offsetSeconds = 100.0, chunkSeconds = 4.0)
        assertEquals(listOf("Hola, doctor.", "Me duele la pierna."), segments.map { it.text })
        assertEquals(100.2, segments[0].start, 1e-6)
        assertEquals(102.0, segments[0].end, 1e-6) // up to where the next sentence starts
        assertEquals(102.0, segments[1].start, 1e-6)
    }

    @Test fun anUnfinishedTailIsKept() {
        val segments = TokenSegmenter.segments("Buenos días y", listOf("${sp}Buenos", "${sp}días", "${sp}y"), listOf(0.1f, 0.4f, 0.9f), 0.0, 1.5)
        assertEquals(listOf("Buenos días y"), segments.map { it.text })
    }

    @Test fun withoutUsableTimestampsTheWholeTextIsOneSegmentOverTheChunk() {
        val segments = TokenSegmenter.segments("  Hola.  ", emptyList(), emptyList(), 30.0, 25.0)
        assertEquals(1, segments.size)
        assertEquals("Hola.", segments[0].text)
        assertEquals(30.0, segments[0].start, 0.0)
        assertEquals(55.0, segments[0].end, 0.0)
        assertEquals(emptyList<TranscriptSegment>(), TokenSegmenter.segments("   ", emptyList(), emptyList(), 0.0, 1.0))
    }
}

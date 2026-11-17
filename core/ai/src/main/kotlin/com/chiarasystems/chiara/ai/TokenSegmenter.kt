package com.chiarasystems.chiara.ai

/**
 * Splits what a token-level recogniser (sherpa-onnx transducers) returned into sentence-sized segments with their time in the
 * audio: the same idea as iOS `SegmentBuilder`. Tokens are SentencePiece pieces ("▁hola", ",", "▁mundo", "."): "▁" marks a
 * word start.
 */
object TokenSegmenter {
    /** A sentence is also closed once it reaches this many characters. */
    const val MAX_CHARS = 240

    /**
     * [tokens] and [timestamps] (seconds from the start of the chunk) are parallel; [offsetSeconds] is where the chunk starts
     * in the recording and [chunkSeconds] its length. Without usable timestamps the whole [text] is one segment.
     */
    fun segments(text: String, tokens: List<String>, timestamps: List<Float>, offsetSeconds: Double, chunkSeconds: Double): List<TranscriptSegment> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (tokens.isEmpty() || tokens.size != timestamps.size) return listOf(TranscriptSegment(offsetSeconds, offsetSeconds + chunkSeconds, trimmed))

        val out = mutableListOf<TranscriptSegment>()
        val buffer = StringBuilder()
        var start = -1.0
        var last = 0.0
        fun flush(end: Double) {
            val sentence = buffer.toString().trim()
            if (sentence.isNotEmpty()) out += TranscriptSegment(offsetSeconds + start, offsetSeconds + maxOf(end, start), sentence)
            buffer.clear(); start = -1.0
        }
        for (i in tokens.indices) {
            val piece = tokens[i].replace('▁', ' ')
            if (start < 0) start = timestamps[i].toDouble()
            buffer.append(piece)
            last = timestamps[i].toDouble()
            val closes = piece.trimEnd().lastOrNull()?.let { it == '.' || it == '?' || it == '!' } == true
            if (closes || buffer.length > MAX_CHARS) {
                // The next token starts after this one ends: use it as the sentence's end when it is there.
                val end = timestamps.getOrNull(i + 1)?.toDouble() ?: (last + 0.4)
                flush(end)
            }
        }
        flush(last + 0.4)
        return out
    }
}

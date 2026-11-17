package com.chiarasystems.chiara.domain

import java.security.SecureRandom
import java.util.UUID

/** Port of iOS `UUID.v7()`. Client-generated ids let a retried create be idempotent (server: on conflict do nothing). */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(nowMillis: Long = System.currentTimeMillis()): UUID {
        val b = ByteArray(16)
        random.nextBytes(b)
        for (i in 0..5) b[i] = (nowMillis ushr (8 * (5 - i))).toByte() // big-endian unix ms
        b[6] = ((b[6].toInt() and 0x0F) or 0x70).toByte()               // version 7
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()               // RFC 4122 variant
        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (b[i].toLong() and 0xFF)
        for (i in 8..15) lsb = (lsb shl 8) or (b[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }
}

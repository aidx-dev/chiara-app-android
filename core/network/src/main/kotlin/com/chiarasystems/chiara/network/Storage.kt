package com.chiarasystems.chiara.network

import java.io.File
import java.security.MessageDigest

/** Port of iOS `Keychain`. Android implementation: Keystore-backed encrypted preferences (in the `app` layer). */
interface SecureStore {
    fun get(account: String): String?
    fun set(value: String, account: String)
    fun remove(account: String)

    companion object Accounts {
        const val REFRESH_TOKEN = "refresh-token"
        const val LAST_EMAIL = "last-email"
    }
}

class InMemorySecureStore : SecureStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(account: String) = values[account]
    override fun set(value: String, account: String) { values[account] = value }
    override fun remove(account: String) { values.remove(account) }
}

/** Read-only offline fallback for list RPCs. Wiped on sign-out. */
interface ResponseCache {
    fun store(data: ByteArray, key: String)
    fun load(key: String): ByteArray?
    fun clear()
}

/** Reversible transformation of cached bytes (the Android layer plugs in Keystore AES-GCM: clinical data at rest). */
interface ByteCodec {
    fun encode(data: ByteArray): ByteArray
    fun decode(data: ByteArray): ByteArray?

    object Identity : ByteCodec {
        override fun encode(data: ByteArray) = data
        override fun decode(data: ByteArray): ByteArray? = data
    }
}

/**
 * Port of iOS `DiskCache`: one file per key (named by the hex SHA-256 of "clinic|rpc|body"), atomic writes, no TTL.
 * [directory] must live outside backups (`noBackupFilesDir/ResponseCache`).
 */
class FileResponseCache(private val directory: File, private val codec: ByteCodec = ByteCodec.Identity) : ResponseCache {
    init { directory.mkdirs() }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) })
    }

    override fun store(data: ByteArray, key: String) {
        runCatching {
            val target = file(key)
            val tmp = File(directory, target.name + ".tmp")
            tmp.writeBytes(codec.encode(data))
            if (!tmp.renameTo(target)) { // Windows refuses to rename over an existing file
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    override fun load(key: String): ByteArray? = runCatching {
        file(key).takeIf { it.exists() }?.readBytes()?.let(codec::decode)
    }.getOrNull()

    override fun clear() {
        directory.deleteRecursively()
        directory.mkdirs()
    }
}

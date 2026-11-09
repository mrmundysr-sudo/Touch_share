package com.touchshare.core

import java.security.MessageDigest
import java.security.SecureRandom

/** One-time session token carried in the QR/URL. Regenerated for every share session. */
object TokenAuth {
    private val rng = SecureRandom()
    private const val HEX = "0123456789abcdef"

    fun generate(bytes: Int = 16): String {
        val b = ByteArray(bytes).also { rng.nextBytes(it) }
        val sb = StringBuilder(bytes * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() shr 4) and 0xF]).append(HEX[x.toInt() and 0xF])
        }
        return sb.toString()
    }

    /** Constant-time comparison; null or empty candidates never match. */
    fun matches(expected: String, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty() || expected.isEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(), candidate.toByteArray())
    }
}

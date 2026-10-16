package com.linguareader.core.content

import com.linguareader.core.model.MediaRef
import java.security.MessageDigest

object Hashing {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
}

sealed interface MediaResult {
    data class Ok(val bytes: ByteArray) : MediaResult
    data object Missing : MediaResult
    data class IntegrityError(val expected: String, val actual: String) : MediaResult
}

object MediaIntegrity {
    /** Si el manifiesto declara sha256, los bytes deben coincidir; si no, se aceptan sin verificar. */
    fun verify(media: MediaRef, bytes: ByteArray?): MediaResult {
        if (bytes == null) return MediaResult.Missing
        val expected = media.sha256 ?: return MediaResult.Ok(bytes)
        val actual = Hashing.sha256Hex(bytes)
        return if (actual == expected) MediaResult.Ok(bytes) else MediaResult.IntegrityError(expected, actual)
    }
}

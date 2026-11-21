package com.nuvio.app.features.usenet

import com.google.re2j.Pattern
import java.security.MessageDigest
import java.text.Normalizer

internal actual object UsenetText {
    actual fun decomposed(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)

    actual fun composed(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    actual fun isLatinLetter(char: Char): Boolean =
        char.isLetter() && Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN

    // Looking a digest up is slow on some devices: one instance serves every hash.
    private val digest = MessageDigest.getInstance("SHA-256")

    actual fun sha256Hex(value: String): String {
        val bytes = synchronized(digest) { digest.digest(value.toByteArray()) }
        val hex = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            hex[i * 2] = HEX[(b.toInt() shr 4) and 0xf]
            hex[i * 2 + 1] = HEX[b.toInt() and 0xf]
        }
        return String(hex)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    actual fun localOffsetMs(epochMs: Long): Long = java.util.TimeZone.getDefault().getOffset(epochMs).toLong()
}

/** RE2J, which rejects lookaround and backreferences and matches in linear time. */
internal actual class UserPattern actual constructor(value: String) {
    private val pattern = Pattern.compile(value, Pattern.CASE_INSENSITIVE)

    actual fun containsMatchIn(input: CharSequence): Boolean = pattern.matcher(input).find()
}

internal actual fun isValidRankingPattern(value: String): Boolean =
    runCatching { Pattern.compile(value, Pattern.CASE_INSENSITIVE) }.isSuccess

package com.nuvio.app.features.usenet

import com.nuvio.app.features.profiles.ProfilePinCrypto
import platform.Foundation.NSDate
import platform.Foundation.NSString
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCanonicalMapping

internal actual object UsenetText {
    actual fun decomposed(value: String): String = NSString.create(string = value).decomposedStringWithCanonicalMapping

    actual fun composed(value: String): String = NSString.create(string = value).precomposedStringWithCanonicalMapping

    actual fun isLatinLetter(char: Char): Boolean = char.isLetter() && LATIN.any { char.code in it }

    actual fun sha256Hex(value: String): String = ProfilePinCrypto.sha256Hex(value)

    actual fun localOffsetMs(epochMs: Long): Long =
        NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)) * 1000L

    /** The blocks of the Latin script in the Basic Multilingual Plane. */
    private val LATIN = listOf(
        0x0041..0x005A, 0x0061..0x007A, 0x00AA..0x00AA, 0x00BA..0x00BA, 0x00C0..0x024F, 0x0250..0x02B8,
        0x02E0..0x02E4, 0x1D00..0x1D25, 0x1D2C..0x1D5C, 0x1D62..0x1D65, 0x1D6B..0x1D77, 0x1D79..0x1DBE,
        0x1E00..0x1EFF, 0x2071..0x2071, 0x207F..0x207F, 0x2090..0x209C, 0x212A..0x212B, 0x2132..0x2132,
        0x214E..0x214E, 0x2184..0x2184, 0x2C60..0x2C7F, 0xA722..0xA787, 0xA78B..0xA7FF, 0xAB30..0xAB5A,
        0xAB5C..0xAB64, 0xAB66..0xAB69, 0xFB00..0xFB06, 0xFF21..0xFF3A, 0xFF41..0xFF5A,
    )
}

/** Kotlin's regex, with the constructs RE2 rejects on Android rejected here too. */
internal actual class UserPattern actual constructor(value: String) {
    private val regex: Regex

    init {
        require(!UNSUPPORTED.containsMatchIn(value)) { "Lookaround and backreferences are not supported" }
        regex = Regex(value, RegexOption.IGNORE_CASE)
    }

    actual fun containsMatchIn(input: CharSequence): Boolean = regex.containsMatchIn(input)

    private companion object {
        val UNSUPPORTED = Regex("""\(\?<?[=!]|\(\?>|\\[1-9]|\\k<|[*+?}]\+""")
    }
}

internal actual fun isValidRankingPattern(value: String): Boolean = runCatching { UserPattern(value) }.isSuccess

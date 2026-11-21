package com.nuvio.app.features.usenet

/** Text operations the Kotlin standard library lacks on every platform. */
internal expect object UsenetText {
    /** Unicode canonical decomposition (NFD). */
    fun decomposed(value: String): String

    /** Unicode canonical composition (NFC). */
    fun composed(value: String): String

    /** Whether [char] is a letter of the Latin script. */
    fun isLatinLetter(char: Char): Boolean

    /** The lowercase hex SHA-256 of [value]'s UTF-8 bytes. */
    fun sha256Hex(value: String): String

    /** The local time zone's offset from UTC at [epochMs], in milliseconds. */
    fun localOffsetMs(epochMs: Long): Long
}

/**
 * A pattern a user wrote in a sorting profile, matched case-insensitively. Lookaround and
 * backreferences are rejected on every platform, so a profile works the same on each and cannot
 * make matching take exponential time.
 */
internal expect class UserPattern(value: String) {
    fun containsMatchIn(input: CharSequence): Boolean
}

/** [value] with [decimals] digits after the point, halves rounded away from zero, for display. */
internal fun formatDecimal(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = kotlin.math.floor(kotlin.math.abs(value) * factor + 0.5).toLong()
    val whole = scaled / factor
    val fraction = (scaled % factor).toString().padStart(decimals, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole.$fraction"
}

/** A map that keeps its [max] most recently used entries; reading an entry uses it. Not thread-safe. */
internal class LruMap<K, V>(private val max: Int) {
    /** Least recently used first. */
    private val entries = LinkedHashMap<K, V>()

    operator fun get(key: K): V? = entries.remove(key)?.also { entries[key] = it }

    operator fun set(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        if (entries.size > max) entries.remove(entries.keys.first())
    }

    fun remove(key: K): V? = entries.remove(key)

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}

/**
 * [template] with its %1$s, %2$s... (or plain %s) placeholders replaced by [args], as String.format
 * fills a translated string's arguments. %% writes a percent sign.
 */
internal fun fillTemplate(template: String, vararg args: Any?): String {
    var next = 0
    return TEMPLATE_PLACEHOLDER.replace(template) { match ->
        when {
            match.value == "%%" -> "%"
            match.groupValues[1].isNotEmpty() -> args.getOrNull(match.groupValues[1].toInt() - 1).toString()
            else -> args.getOrNull(next++).toString()
        }
    }
}

private val TEMPLATE_PLACEHOLDER = Regex("%%|%(?:([0-9]+)[$])?s")

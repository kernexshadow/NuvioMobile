package com.nuvio.app.features.usenet

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * [value] as an http or https URL with a valid host and port, as indexer endpoints and NZB links
 * must be; null otherwise. Stricter than Ktor's parser, which reads almost any text as a URL.
 */
internal fun httpUrlOrNull(value: String): Url? {
    val text = value.trim()
    val authority = HTTP_AUTHORITY.find(text)?.groupValues?.get(1) ?: return null
    if (authority.contains('@')) {
        // Credentials are accepted here and rejected where an endpoint is validated.
        if (authority.substringAfterLast('@').isEmpty()) return null
    }
    val url = runCatching { Url(text) }.getOrNull() ?: return null
    if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return null
    if (!isValidHost(url.host) || url.port !in 1..65535) return null
    return url
}

/** A host name, an IPv4 address or an IPv6 address, bracketed or not. */
internal fun isValidHost(host: String): Boolean {
    val bare = host.removeSurrounding("[", "]")
    if (bare.isEmpty() || bare.length > 253) return false
    if (bare.contains(':')) return IPV6.matches(bare)
    return bare.split('.').all { label -> label.isNotEmpty() && label.length <= 63 && HOST_LABEL.matches(label) }
}

private val HTTP_AUTHORITY = Regex("^(?i)https?://([^/?#\\s]+)")
private val HOST_LABEL = Regex("[\\p{L}\\p{N}_-]+")
private val IPV6 = Regex("[0-9A-Fa-f:.]+(%[\\w.]+)?")

internal fun Url.queryParameter(name: String): String? = parameters[name]

internal fun URLBuilder.setQueryParameter(name: String, value: String): URLBuilder = apply { parameters[name] = value }

/**
 * [reference] resolved against this URL as RFC 3986 does, as a link on a page is: an absolute URL
 * stands alone, a path replaces this one's last segment, and a reference without a query has none.
 * Null when the result is no valid http(s) URL.
 */
internal fun Url.resolve(reference: String): Url? {
    val ref = reference.trim()
    if (SCHEME.containsMatchIn(ref)) return httpUrlOrNull(ref)
    val origin = buildString {
        append(protocol.name).append("://").append(if (host.contains(':') && !host.startsWith("[")) "[$host]" else host)
        if (specifiedPort != 0 && specifiedPort != protocol.defaultPort) append(':').append(specifiedPort)
    }
    val path = encodedPath.ifEmpty { "/" }
    val resolved = when {
        ref.startsWith("//") -> "${protocol.name}:$ref"
        ref.startsWith("/") -> origin + removeDotSegments(ref)
        ref.isEmpty() || ref.startsWith("#") -> origin + path + (encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
        ref.startsWith("?") -> origin + path + ref
        else -> origin + removeDotSegments(path.substringBeforeLast('/') + "/" + ref)
    }
    return httpUrlOrNull(resolved.substringBefore('#'))
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** Applies the "." and ".." segments of [reference]'s path; its query is left as it is. */
private fun removeDotSegments(reference: String): String {
    val query = reference.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) reference.length else it }
    val output = ArrayDeque<String>()
    val segments = reference.substring(0, query).split('/')
    segments.forEachIndexed { index, segment ->
        when (segment) {
            "." -> if (index == segments.lastIndex) output.addLast("")
            ".." -> {
                if (output.size > 1) output.removeLast()
                if (index == segments.lastIndex) output.addLast("")
            }
            else -> output.addLast(segment)
        }
    }
    return output.joinToString("/").let { if (it.startsWith("/")) it else "/$it" } + reference.substring(query)
}

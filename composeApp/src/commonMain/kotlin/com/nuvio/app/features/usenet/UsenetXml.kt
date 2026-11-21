package com.nuvio.app.features.usenet

/**
 * An element of an indexer's XML answer. Holds what Newznab answers use: names, attributes and
 * text. Comments and processing instructions are dropped.
 */
internal class UsenetXmlElement(
    /** The name as written, prefix included, as in newznab:attr. */
    val qualifiedName: String,
    private val attributes: Map<String, String>,
) {
    /** Child elements and text, in document order. */
    internal val nodes = mutableListOf<Any>()

    val localName: String get() = qualifiedName.substringAfter(':')

    /** The attribute's value, or "" when it is absent, as DOM's getAttribute. */
    fun attribute(name: String): String = attributes[name].orEmpty()

    /** The text of this element and of every element in it, as DOM's textContent. */
    val textContent: String
        get() = buildString { appendText(this) }

    private fun appendText(out: StringBuilder) {
        for (node in nodes) if (node is UsenetXmlElement) node.appendText(out) else out.append(node as String)
    }

    /** The elements in this one named [name] as written, in document order, as DOM's getElementsByTagName. */
    fun elementsNamed(name: String): List<UsenetXmlElement> = descendants { it.qualifiedName == name }

    /** The elements in this one with local name [name] in any namespace, as getElementsByTagNameNS("*", name). */
    fun elementsLocallyNamed(name: String): List<UsenetXmlElement> = descendants { it.localName == name }

    private fun descendants(keep: (UsenetXmlElement) -> Boolean): List<UsenetXmlElement> {
        val found = mutableListOf<UsenetXmlElement>()
        fun walk(element: UsenetXmlElement) {
            for (node in element.nodes) if (node is UsenetXmlElement) {
                if (keep(node)) found += node
                walk(node)
            }
        }
        walk(this)
        return found
    }
}

/**
 * A small XML reader for indexer answers, the same on every platform. It rejects what Newznab never
 * sends and a parser could be attacked with: document types and entity declarations. Throws
 * [IllegalArgumentException] for anything that is not well-formed.
 */
internal object UsenetXml {
    fun parse(xml: String): UsenetXmlElement = Reader(xml).document()

    private class Reader(private val text: String) {
        private var i = if (text.startsWith('\uFEFF')) 1 else 0

        fun document(): UsenetXmlElement {
            var root: UsenetXmlElement? = null
            val open = ArrayDeque<UsenetXmlElement>()
            while (i < text.length) {
                if (text[i] != '<') {
                    val end = text.indexOf('<', i).let { if (it < 0) text.length else it }
                    val chunk = decode(text, i, end)
                    if (open.isEmpty()) require(chunk.isBlank()) { "Text outside the root element" }
                    else open.last().nodes += chunk
                    i = end
                    continue
                }
                when {
                    text.startsWith("<?", i) -> i = after("?>")
                    text.startsWith("<!--", i) -> i = after("-->")
                    text.startsWith("<![CDATA[", i) -> {
                        require(open.isNotEmpty()) { "CDATA outside the root element" }
                        val end = text.indexOf("]]>", i + 9)
                        require(end >= 0) { "Unterminated CDATA section" }
                        open.last().nodes += text.substring(i + 9, end)
                        i = end + 3
                    }
                    text.startsWith("<!", i) -> throw IllegalArgumentException("Document types are not accepted")
                    text.startsWith("</", i) -> {
                        val end = text.indexOf('>', i)
                        require(end >= 0) { "Unterminated end tag" }
                        val name = text.substring(i + 2, end).trim()
                        require(open.isNotEmpty() && open.last().qualifiedName == name) { "Mismatched end tag" }
                        open.removeLast()
                        i = end + 1
                    }
                    else -> {
                        val (element, empty) = startTag()
                        if (open.isEmpty()) {
                            require(root == null) { "More than one root element" }
                            root = element
                        } else {
                            open.last().nodes += element
                        }
                        if (!empty) open.addLast(element)
                    }
                }
            }
            require(open.isEmpty()) { "Unclosed element" }
            return requireNotNull(root) { "No root element" }
        }

        private fun after(terminator: String): Int {
            val end = text.indexOf(terminator, i)
            require(end >= 0) { "Unterminated markup" }
            return end + terminator.length
        }

        private fun startTag(): Pair<UsenetXmlElement, Boolean> {
            i++ // <
            val name = name()
            val attributes = LinkedHashMap<String, String>()
            while (true) {
                skipSpace()
                require(i < text.length) { "Unterminated start tag" }
                when {
                    text[i] == '>' -> { i++; return UsenetXmlElement(name, attributes) to false }
                    text.startsWith("/>", i) -> { i += 2; return UsenetXmlElement(name, attributes) to true }
                    else -> {
                        val attribute = name()
                        skipSpace()
                        require(i < text.length && text[i] == '=') { "Attribute without a value" }
                        i++
                        skipSpace()
                        require(i < text.length && (text[i] == '"' || text[i] == '\'')) { "Unquoted attribute value" }
                        val quote = text[i]
                        val end = text.indexOf(quote, i + 1)
                        require(end >= 0) { "Unterminated attribute value" }
                        require(text.indexOf('<', i + 1).let { it < 0 || it > end }) { "Invalid attribute value" }
                        // Attribute-value normalization: line breaks and tabs read as spaces.
                        attributes[attribute] = decode(text, i + 1, end).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                        i = end + 1
                    }
                }
            }
        }

        private fun name(): String {
            val start = i
            while (i < text.length && !text[i].isWhitespace() && text[i] !in "/>=<\"'") i++
            require(i > start) { "Missing name" }
            return text.substring(start, i)
        }

        private fun skipSpace() {
            while (i < text.length && text[i].isWhitespace()) i++
        }
    }

    /** [text] from [start] to [end] with its character and predefined entity references replaced. */
    private fun decode(text: String, start: Int, end: Int): String {
        val amp = text.indexOf('&', start)
        if (amp < 0 || amp >= end) return text.substring(start, end).replace("\r\n", "\n")
        val out = StringBuilder(end - start)
        var i = start
        while (i < end) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semicolon = text.indexOf(';', i)
            require(semicolon in (i + 1) until end) { "Unterminated entity reference" }
            val entity = text.substring(i + 1, semicolon)
            when {
                entity.startsWith("#x") || entity.startsWith("#X") -> appendCodePoint(out, entity.substring(2).toInt(16))
                entity.startsWith("#") -> appendCodePoint(out, entity.substring(1).toInt())
                else -> out.append(
                    when (entity) {
                        "lt" -> '<'
                        "gt" -> '>'
                        "amp" -> '&'
                        "quot" -> '"'
                        "apos" -> '\''
                        else -> throw IllegalArgumentException("Undefined entity")
                    },
                )
            }
            i = semicolon + 1
        }
        return out.toString().replace("\r\n", "\n")
    }

    private fun appendCodePoint(out: StringBuilder, codePoint: Int) {
        require(codePoint in 1..0x10FFFF && codePoint !in 0xD800..0xDFFF) { "Invalid character reference" }
        if (codePoint < 0x10000) {
            out.append(codePoint.toChar())
        } else {
            val offset = codePoint - 0x10000
            out.append((0xD800 + (offset shr 10)).toChar()).append((0xDC00 + (offset and 0x3FF)).toChar())
        }
    }
}

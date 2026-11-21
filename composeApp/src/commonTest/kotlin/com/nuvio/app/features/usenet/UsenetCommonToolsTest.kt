package com.nuvio.app.features.usenet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The platform-independent readers that replaced the JVM's for indexer answers. */
class UsenetCommonToolsTest {
    @Test fun `xml elements, attributes and text are read as DOM reads them`() {
        val root = UsenetXml.parse(
            "\uFEFF" + """<?xml version="1.0" encoding="UTF-8"?>
            <!-- an indexer answer -->
            <rss xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">
              <channel>
                <newznab:response offset="0" total="2"/>
                <item>
                  <title>A &amp; B &#233;&#x1F600; <![CDATA[<raw>]]></title>
                  <media:title>Not the title</media:title>
                  <enclosure url="https://x.test/get?a=1&amp;b=2" length='10' type="application/x-nzb"/>
                  <newznab:attr name="size" value="123"/>
                </item>
              </channel>
            </rss>""",
        )
        assertEquals("rss", root.localName)
        val item = root.elementsNamed("item").single()
        assertEquals("A & B é😀 <raw>", item.elementsNamed("title").single().textContent)
        assertEquals("https://x.test/get?a=1&b=2", item.elementsNamed("enclosure").single().attribute("url"))
        assertEquals("10", item.elementsNamed("enclosure").single().attribute("length"))
        assertEquals("", item.elementsNamed("enclosure").single().attribute("missing"))
        assertEquals("123", item.elementsLocallyNamed("attr").single().attribute("value"))
        assertEquals("2", root.elementsLocallyNamed("response").single().attribute("total"))
        // A prefixed element with the same local name is not the element.
        assertEquals(1, item.elementsNamed("title").size)
        assertEquals(2, item.elementsLocallyNamed("title").size)
    }

    @Test fun `malformed xml and document types are rejected`() {
        for (xml in listOf("", "<a>", "<a></b>", "<a/><b/>", "text<a/>", "<a x=1/>", "<a>&unknown;</a>", "<!DOCTYPE a><a/>",
            "<a><![CDATA[x</a>", "<a b=\"<\"/>")) {
            assertFailsWith<IllegalArgumentException>(xml) { UsenetXml.parse(xml) }
        }
    }

    @Test fun `dates are read as RFC 1123, ISO 8601 or epoch counts`() {
        assertEquals(1_763_738_648_000, NewznabDates.parse("Fri, 21 Nov 2025 15:24:08 +0000"))
        assertEquals(1_763_738_648_000, NewznabDates.parse("21 Nov 2025 16:24:08 +0100"))
        assertEquals(1_763_738_640_000, NewznabDates.parse("Fri, 21 Nov 2025 15:24 GMT"))
        assertEquals(1_763_738_648_000, NewznabDates.parse("2025-11-21T15:24:08Z"))
        assertEquals(1_763_738_648_000, NewznabDates.parse("1763738648"))
        assertEquals(1_763_738_648_000, NewznabDates.parse("1763738648000"))
        assertEquals(951_782_400_000, NewznabDates.parse("Tue, 29 Feb 2000 00:00:00 GMT"))
        assertNull(NewznabDates.parse("Fri, 30 Feb 2025 15:24:08 +0000"))
        assertNull(NewznabDates.parse("yesterday"))
        assertNull(NewznabDates.parse("Fri, 21 Nov 2025 15:24:08 EST"))
    }

    @Test fun `links resolve against the endpoint as RFC 3986 does`() {
        val base = httpUrlOrNull("https://indexer.test/api/v1?apikey=k&t=search")!!
        assertEquals("https://other.test/get?id=1", base.resolve("https://other.test/get?id=1").toString())
        assertEquals("https://indexer.test/getnzb/1.nzb?i=2", base.resolve("/getnzb/1.nzb?i=2").toString())
        assertEquals("https://indexer.test/api/getnzb?id=3", base.resolve("getnzb?id=3").toString())
        assertEquals("https://indexer.test/getnzb", base.resolve("../getnzb").toString())
        assertEquals("https://cdn.test/x.nzb", base.resolve("//cdn.test/x.nzb").toString())
        assertEquals("https://indexer.test/api/v1?id=4", base.resolve("?id=4").toString())
        assertNull(base.resolve("ftp://indexer.test/x.nzb"))
        assertNull(base.resolve("javascript:alert(1)"))
    }

    @Test fun `only http urls with valid hosts are accepted`() {
        assertEquals("http://prowlarr.local:9696/1/api", httpUrlOrNull(" http://prowlarr.local:9696/1/api ").toString())
        assertEquals("https://[::1]/api", httpUrlOrNull("https://[::1]/api").toString())
        for (bad in listOf("", "indexer.test/api", "ftp://indexer.test", "https://", "https://bad host/", "https://a..b/", "https://x.test:99999/")) {
            assertNull(httpUrlOrNull(bad), bad)
        }
    }

    @Test fun `provider accounts are named by host, port and username`() {
        val provider = UsenetProvider(host = "News.Example.com", port = 563, username = "me+you@x", password = "secret:1")
        assertEquals("news.example.com:563\nme+you@x", providerAccount(provider.serverUrl()))
        assertEquals("[::1]:119\n", providerAccount(UsenetProvider(host = "::1", port = 119, tls = false).serverUrl()))
        assertEquals("host.test:119\n", providerAccount("nntp://host.test/20"))
        assertEquals("host.test:563\n", providerAccount("nntps://host.test/20"))
        assertNull(providerAccount("not a url"))
        assertNull(providerAccount(null))
    }

    @Test fun `decimals are written as the root locale writes them`() {
        assertEquals("1.50", formatDecimal(1.5, 2))
        assertEquals("0.00", formatDecimal(0.0, 2))
        assertEquals("1.23", formatDecimal(1.234, 2))
        assertEquals("1.24", formatDecimal(1.236, 2))
        assertEquals("-1.25", formatDecimal(-1.25, 2))
        assertEquals("3", formatDecimal(2.6, 0))
        assertEquals("-1", formatDecimal(-0.5, 0))
    }

    @Test fun `the lru map keeps the most recently used entries`() {
        val map = LruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        assertEquals(1, map["a"])
        map["c"] = 3
        assertNull(map["b"])
        assertEquals(1, map["a"])
        assertEquals(3, map["c"])
        assertEquals(2, map.size)
    }

    @Test fun `user patterns reject lookaround and backreferences`() {
        assertTrue(isValidRankingPattern("(?:x26[45]|hevc)"))
        for (pattern in listOf("(?=x)", "(?!x)", "(?<=x)", "(?<!x)", "(a)\\1", "(", "a++")) {
            assertTrue(!isValidRankingPattern(pattern), pattern)
        }
        assertTrue(UserPattern("HEVC").containsMatchIn("Movie.2025.1080p.hevc-GRP"))
    }
}

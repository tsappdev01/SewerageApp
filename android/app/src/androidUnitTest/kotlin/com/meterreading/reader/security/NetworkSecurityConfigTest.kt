package com.meterreading.reader.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Android 3.4: what the release build trusts and allows, read from the files the build uses. */
class NetworkSecurityConfigTest {
    private fun read(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    @Test fun SEC_release_allows_no_plain_http_and_trusts_only_the_phones_own_authorities() {
        val config = read("src/androidMain/res/xml/network_security_config.xml")
        val base = config.children("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertEquals(listOf("system"), config.children("certificates").map { it.getAttribute("src") })
        assertTrue(config.children("domain-config").isEmpty())
        assertTrue(config.children("debug-overrides").isEmpty())
    }

    @Test fun SEC_debug_allows_plain_http_only_to_the_development_computer() {
        val config = read("src/androidDebug/res/xml/network_security_config.xml")
        assertEquals("false", config.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
        val domains = config.children("domain-config").single().children("domain").map { it.textContent.trim() }
        assertEquals(setOf("10.0.2.2", "localhost", "127.0.0.1"), domains.toSet())
    }

    @Test fun SEC_the_manifest_uses_the_config() {
        val manifest = File("src/androidMain/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(!manifest.contains("usesCleartextTraffic"))
    }
}

// SPDX-FileCopyrightText: 2026 Christoph Langner
// SPDX-License-Identifier: GPL-3.0-or-later
// Sonos handling based on noson (libnoson) by Jean-Luc Barrière, GPL-3.0-or-later; see NOTICE.md.

package de.linuxundich.hausmusik.sonos

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Minimal DOM helpers. DOM is available on the JVM and on Android alike, unlike
 * StAX (missing on Android) or XmlPullParser (missing on the JVM).
 *
 * Parsing is deliberately not namespace aware: Sonos uses fixed prefixes
 * (`dc:`, `upnp:`, `r:`), and matching on the local name is all we need.
 */
internal object Xml {
    private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }

    fun parse(xml: String): Element =
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
}

internal val Node.localName0: String
    get() = nodeName.substringAfter(':')

internal fun Element.children(): Sequence<Element> = sequence {
    var n = firstChild
    while (n != null) {
        if (n is Element) yield(n)
        n = n.nextSibling
    }
}

internal fun Element.child(name: String): Element? = children().firstOrNull { it.localName0 == name }

internal fun Element.childText(name: String): String? = child(name)?.textContent

/** Depth-first search for the first element with the given local name. */
internal fun Element.find(name: String): Element? {
    if (localName0 == name) return this
    for (c in children()) c.find(name)?.let { return it }
    return null
}

internal fun Element.findAll(name: String): List<Element> {
    val out = mutableListOf<Element>()
    fun walk(e: Element) {
        if (e.localName0 == name) out += e
        e.children().forEach(::walk)
    }
    walk(this)
    return out
}

internal fun Element.attr(name: String): String? = getAttribute(name).takeIf { hasAttribute(name) }

internal fun String.xmlEscape(): String = buildString(length) {
    for (c in this@xmlEscape) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(c)
    }
}

package com.arjun.gander

/**
 * The wire between ViewerActivity and pdf.html.
 *
 * One HTML5 message channel carries every word spoken in either direction, and
 * it is deliberately not addJavascriptInterface: that call reflects a Java
 * object into script running on an untrusted document and lets it call methods
 * on it. This passes strings, in the same direction the query parameters on the
 * URL already go. Nothing on either side is evaluated.
 *
 * That makes the parsing the whole of the security boundary, which is why it
 * lives in its own file with its own tests. A document that can reach this can
 * say anything, so everything it says is read as a small fixed shape or
 * dropped. There is no error path on purpose: an unreadable message is not
 * worth reporting to a page that may have sent it deliberately.
 *
 * Outbound, in [PortCommand], is one letter of verb then arbitrary payload.
 * Inbound is space separated and read here.
 */

/** What ViewerActivity sends. One letter, then the rest is payload. */
internal object PortCommand {
    /** Set the query. Arbitrary text, never parsed as anything but text. */
    fun query(q: String) = "q$q"

    fun next() = "n"

    fun prev() = "p"

    fun clear() = "c"

    /** Go to a page, one based. Bounds are checked again inside the page. */
    fun goToPage(n: Int) = "g$n"

    /**
     * Turn the pages over, or back. The second verb here that is not about
     * finding something, after [goToPage]; the channel is named for search
     * because search is what it was opened for, not because that is all it says.
     */
    fun nightMode(on: Boolean) = if (on) "i1" else "i0"

    /**
     * How tall the title bar over a document is, in dp, which the page keeps clear at its
     * top. See [DocumentChrome].
     */
    fun barHeight(dp: String) = "t$dp"

    /**
     * How much of the title bar over a document has gone up out of sight, in dp: 0 while all of
     * it is on screen.
     */
    fun barGone(dp: String) = "b$dp"

    /**
     * Presentation mode on or off: one page at a time, whole, for a document being shown
     * to somebody rather than read. See [PortMessage.Presenting] for the answer.
     *
     * The page owns this one rather than the app, unlike night mode. It is the page that
     * knows whether there is a document open to present and what shape its pages are, and
     * the app has to put its own bar and the phone's bars away for it, which it does when
     * the answer comes back rather than when this is sent.
     */
    fun play(on: Boolean) = if (on) "s1" else "s0"
}

/** What pdf.html sends back, once it has been read and found sound. */
internal sealed interface PortMessage {

    /**
     * Which page is under the middle of the screen. Tagged, because the search
     * message is three bare numbers and the two would otherwise be the same
     * shape.
     */
    data class Page(val n: Int, val of: Int) : PortMessage

    /**
     * The search counter. [at] counts from one and is zero when there is
     * nothing to be at, so the readout never says "1 of 0". [done] is false
     * while the index is still being built.
     */
    data class SearchCount(val at: Int, val total: Int, val done: Boolean) : PortMessage

    /** The PDF asked for a password, which Android's printing has no way to give it. */
    data object Locked : PortMessage

    /**
     * The presentation is up or down, and whether the page on screen is wider than it is
     * tall.
     *
     * [wide] is the orientation the app is asked to hold the phone at, and it is sent
     * again on every page turned rather than once on the way in: a document is free to mix
     * shapes, and a deck with a portrait cover is the case that makes it worth saying
     * twice. It is meaningless while [on] is false, and the page sends zero there.
     */
    data class Presenting(val on: Boolean, val wide: Boolean) : PortMessage
}

/**
 * Reads one message off the port, or null for anything that is not one.
 *
 * Null covers a message of the wrong length, a field that is not an integer,
 * and a set of integers that could not describe a real document: a page before
 * the first or after the last, or a negative count. The caller drops those
 * silently.
 */
internal fun parsePortMessage(data: String?): PortMessage? {
    if (data == "locked") return PortMessage.Locked
    val said = data?.split(" ") ?: return null
    if (said.size != 3) return null

    // Read first, because "page" is not an integer and so was already being
    // dropped by the search branch before this one existed.
    if (said[0] == "page") {
        val n = said[1].toIntOrNull() ?: return null
        val of = said[2].toIntOrNull() ?: return null
        if (n < 1 || of < 1 || n > of) return null
        return PortMessage.Page(n, of)
    }

    // The presentation, which is the second tagged message and the only one whose fields
    // are flags rather than numbers. Anything but the two words is not a flag: 1 and 0 are
    // what the page writes, and a message saying anything else came from a document.
    if (said[0] == "present") {
        val on = flag(said[1]) ?: return null
        val wide = flag(said[2]) ?: return null
        return PortMessage.Presenting(on, wide)
    }

    val at = said[0].toIntOrNull() ?: return null
    val total = said[1].toIntOrNull() ?: return null
    if (at < 0 || total < 0) return null
    return PortMessage.SearchCount(at, total, said[2] == "1")
}

/** One of the two words the page uses for a flag, or null for anything else. */
private fun flag(said: String): Boolean? = when (said) {
    "1" -> true
    "0" -> false
    else -> null
}

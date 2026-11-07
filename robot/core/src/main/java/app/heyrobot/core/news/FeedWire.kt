// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

// Today H7b (home-design §6.1, "Limits and cost"): anyone's server can send anything, so a feed's bytes are read in
// :feedparse, an isolated process modelled on :linkparse (no network, no files, none of Robot's stores). It answers one
// fixed frame (FeedWire.answer, there); the caller trusts nothing in it: check reads it strictly and cleans every name,
// title and link again with the same pure rules (links https only), so a reader that was taken over can drop items but never add a dirty one.

/** :feedparse's Binder protocol: numbers and bytes, never an exception. */
object FeedParseIpc {
    const val SERVICE_CLASS = "app.heyrobot.feedparse.FeedParseService" // l10n: not text (a class name)
    const val DESCRIPTOR = "app.heyrobot.feedparse.IFeedParse" // l10n: not text (a Binder descriptor)
    const val CALLBACK = "app.heyrobot.feedparse.IFeedParseCallback" // l10n: not text (a Binder descriptor)
    /** In: the caller's callback; the process calls it back (CB_HELLO) from its own uid. Out: REPLY_OK, 1. */
    const val HELLO = 1
    /** In: the base address (at most [MAX_BASE]), the time, 1 and a read-only region of the bytes. Out: REPLY_OK, one frame. */
    const val READ = 2
    /** One way: the process ends. */
    const val EXIT = 3
    const val CB_HELLO = 1
    const val REPLY_OK = 0
    const val REPLY_REFUSED = 1
    const val REPLY_NOT_ISOLATED = 2
    const val MAX_BASE = 2_048
    const val MAX_REPLY = 128 * 1024
    const val BIND_MS = 8_000L
    const val CALL_MS = 5_000L
    /** The reader's own watchdog: past it the process ends itself. */
    const val SERVICE_MS = 4_000L
}

/** What :feedparse found in a reply's bytes: a feed, or the feeds a web page names (Find), or neither. */
sealed class FeedAnswer {
    data class Feed(val feed: ParsedFeed) : FeedAnswer()
    data class Links(val links: List<String>) : FeedAnswer()
}

object FeedWire {
    private const val MAGIC = 0x46 // 'F'
    private const val VERSION = 1
    private const val FEED = 1
    private const val LINKS = 2
    const val MAX_LINKS = 3

    /** In :feedparse: [bytes] read as a feed, else as a page naming feeds. Pure and capped (FeedXml, FeedFind). */
    fun answer(bytes: ByteArray, base: String, now: Long): ByteArray {
        val feed = if (bytes.size <= FeedXml.MAX_BYTES) FeedRead.read(bytes, base, now) else null
        val links = if (feed == null && bytes.size <= FeedXml.MAX_BYTES) FeedFind.links(String(bytes, Charsets.UTF_8), base).take(MAX_LINKS) else emptyList()
        return encode(feed?.let { FeedAnswer.Feed(it) } ?: FeedAnswer.Links(links))
    }

    fun encode(a: FeedAnswer): ByteArray {
        val b = ByteArrayOutputStream()
        DataOutputStream(b).apply {
            writeByte(MAGIC); writeByte(VERSION)
            when (a) {
                is FeedAnswer.Feed -> {
                    writeByte(FEED); writeUTF(a.feed.name); writeShort(a.feed.items.size)
                    for (i in a.feed.items) { writeUTF(i.title); writeUTF(i.link); writeLong(i.at) }
                }
                is FeedAnswer.Links -> { writeByte(LINKS); writeShort(a.links.size); for (l in a.links) writeUTF(l) }
            }
            flush()
        }
        return b.toByteArray()
    }

    /**
     * [frame] as the caller keeps it, or null when it isn't exactly one well-formed frame within the caps. Every field is
     * cleaned again (FeedText, FeedLinks: https only, trackers off); an item that doesn't survive is dropped.
     */
    fun check(frame: ByteArray, now: Long): FeedAnswer? = try {
        if (frame.size > FeedParseIpc.MAX_REPLY) null
        else {
            val d = DataInputStream(ByteArrayInputStream(frame))
            if (d.readUnsignedByte() != MAGIC || d.readUnsignedByte() != VERSION) null
            else when (d.readUnsignedByte()) {
                FEED -> {
                    val name = FeedText.clean(d.readUTF(), FeedRead.NAME_MAX)
                    val n = d.readUnsignedShort()
                    if (n > FeedRead.MAX_ITEMS || name.isEmpty()) null
                    else {
                        val items = (0 until n).mapNotNull {
                            val t = FeedText.clean(d.readUTF(), FeedRead.TITLE_MAX)
                            val l = FeedLinks.clean(d.readUTF(), null)?.takeIf { it.startsWith("https://") }
                            val at = d.readLong()
                            if (t.isEmpty() || l == null || at > now + FeedRead.AHEAD_MS || at < 0) null else FeedItem(t, l, at)
                        }
                        if (d.available() != 0) null else FeedAnswer.Feed(ParsedFeed(name, items))
                    }
                }
                LINKS -> {
                    val n = d.readUnsignedShort()
                    if (n > MAX_LINKS) null
                    else {
                        val links = (0 until n).mapNotNull { FeedLinks.clean(d.readUTF(), null)?.takeIf { it.startsWith("https://") } }
                        if (d.available() != 0) null else FeedAnswer.Links(links)
                    }
                }
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }
}

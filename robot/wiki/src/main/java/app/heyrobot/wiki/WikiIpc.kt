// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

import android.os.Parcel

/**
 * The :wiki process's binder calls (WikiService answers, WikiLink asks). Each call: the interface token, then its
 * arguments; each reply: a status, then (OK only) its values. Both sides read only what they expect, with bounds, and
 * anything else is a refusal.
 */
internal object WikiIpc {
    const val DESCRIPTOR = "app.heyrobot.wiki.IWiki" // l10n: not text (a binder interface name)

    const val OPEN = 1 // a ParcelFileDescriptor, read-only; reply: WikiInfo
    const val TITLES = 2 // query, max; reply: hits
    const val SEARCH = 3 // query, max; reply: hits
    const val ARTICLE = 4 // path; reply: path, title, html
    const val EXIT = 5 // one-way: the process ends

    const val REPLY_OK = 0
    const val REPLY_REFUSED = 1
    const val REPLY_NOT_ISOLATED = 2
    const val REPLY_NO_LIBRARY = 3
    const val REPLY_NOT_OPEN = 4
    const val REPLY_NOT_ZIM = 5
    const val REPLY_NONE = 6
    /** Never sent: the caller's own, when the :wiki process died and binding again failed (WikiLink.gone). */
    const val REPLY_GONE = 7

    /** The process ends itself after this long without a call (the plan: a minute idle). */
    const val IDLE_MS = 60_000L

    fun writeInfo(p: Parcel, i: WikiInfo) {
        p.writeLong(i.articles)
        p.writeInt(if (i.titleIndex) 1 else 0)
        p.writeInt(if (i.fullText) 1 else 0)
        p.writeString(i.title)
        p.writeString(i.language)
        p.writeString(i.date)
    }

    fun readInfo(p: Parcel): WikiInfo? {
        val n = p.readLong()
        val t = p.readInt()
        val f = p.readInt()
        val title = p.readString()
        val lang = p.readString()
        val date = p.readString()
        if (n < 0 || t !in 0..1 || f !in 0..1 || listOf(title, lang, date).any { (it?.length ?: 0) > WikiReader.MAX_META }) return null
        return WikiInfo(n, t == 1, f == 1, title, lang, date)
    }

    fun writeHits(p: Parcel, hits: List<WikiHit>) {
        p.writeInt(hits.size)
        for (h in hits) {
            p.writeString(h.path)
            p.writeString(h.title)
        }
    }

    fun readHits(p: Parcel): List<WikiHit>? {
        val n = p.readInt()
        if (n !in 0..WikiReader.MAX_HITS) return null
        return List(n) {
            val path = p.readString()
            val title = p.readString()
            if (path.isNullOrBlank() || path.length > WikiReader.MAX_PATH || title == null || title.length > WikiReader.MAX_TITLE) return null
            WikiHit(path, title)
        }
    }

    fun writeArticle(p: Parcel, a: WikiArticle) {
        p.writeString(a.path)
        p.writeString(a.title)
        p.writeString(a.html)
    }

    fun readArticle(p: Parcel): WikiArticle? {
        val path = p.readString()
        val title = p.readString()
        val html = p.readString()
        if (path.isNullOrBlank() || path.length > WikiReader.MAX_PATH || title == null || title.length > WikiReader.MAX_TITLE) return null
        if (html.isNullOrBlank() || html.length > WikiReader.MAX_HTML_BYTES) return null
        return WikiArticle(path, title, html)
    }
}

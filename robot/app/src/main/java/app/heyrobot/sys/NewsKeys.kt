// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.SecretRead
import app.heyrobot.core.news.NewsFeed
import app.heyrobot.core.news.NewsSecret

// Main and voice processes. Today H8: a feed address that carries a token (core NewsSecret) is kept here only, encrypted
// with its own Keystore key (robot_feed_<id>) in files/robot/news_feed_<id>.bin, its masked form beside it for the dialog.
// home_settings.json keeps the feed's stand-in. It is decrypted only in NewsStore, just before the feed's request, and never
// logged, put in an intent, History, IPC or the backup (a restored feed with no key fails until you add it again).
object NewsKeys {
    private const val PREFIX = "news_feed_" // l10n: not text (a file name)

    private fun store(id: String) = KeystoreSecret("robot_feed_$id", PREFIX + id, "news_feed") // l10n: not text (a Keystore alias, a file name)

    /** [url] (one that carries a token) saved under a new id: its feed, or null if it couldn't be saved. */
    fun feed(ctx: Context, url: String, name: String): NewsFeed? {
        if (!NewsSecret.carries(url)) return NewsFeed(url, name)
        val id = NewsSecret.id()
        val standIn = NewsSecret.standIn(url, id) ?: return null
        return if (store(id).save(ctx, url, NewsSecret.masked(url))) NewsFeed(standIn, name, id) else null
    }

    /** The address [f] is fetched at: its own, or the one its key file holds (null: none, or not readable now). */
    fun address(ctx: Context, f: NewsFeed): String? {
        val key = f.key ?: return f.url
        return (store(key).read(ctx) as? SecretRead.Present)?.value?.takeIf { NewsSecret.isStandIn(f.url, key) && NewsSecret.origin(it) == NewsSecret.origin(f.url) }
    }

    /** Whether [url], an address with a token, is already one of [feeds] (today-h8-fix 8): the screen gets a yes or no, never the address. */
    fun has(ctx: Context, feeds: List<NewsFeed>, url: String): Boolean = feeds.any { address(ctx, it) == url }

    /** What the dialog shows for [f]: its address with the token masked, or null when it has none. */
    fun masked(ctx: Context, f: NewsFeed): String? = f.key?.let { store(it).masked(ctx) }

    /** The key files of feeds no longer in [keep] are deleted (a feed removed, Today turned off, a restore). */
    fun prune(ctx: Context, keep: Collection<NewsFeed>) {
        val ids = keep.mapNotNull { it.key }.toSet()
        Paths.dir(ctx).listFiles()?.map { it.name }?.filter { it.startsWith(PREFIX) && it.endsWith(".bin") }
            ?.map { it.removePrefix(PREFIX).removeSuffix(".bin") }?.filter { it !in ids }?.forEach { store(it).delete(ctx) }
    }
}

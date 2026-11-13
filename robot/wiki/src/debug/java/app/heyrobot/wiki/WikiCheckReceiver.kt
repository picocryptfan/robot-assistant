// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.File

// Debug build only: P1's phone check (debug/AndroidManifest.xml says how it is sent). The newest .zim file in Robot's
// outside folder's wiki/ is copied into filesDir/wiki (where P3's download will move it, and where the isolated process
// can read a descriptor of it), opened in :wiki, and asked for "Louis Riel" (or the "q" extra) by title and by full text,
// then for the first hit's article. The broadcast's result data is the report; nothing is logged.
class WikiCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val q = intent.getStringExtra("q")?.take(WikiReader.MAX_QUERY) ?: "Louis Riel" // l10n: not text (a debug check)
        Thread {
            val out = try { check(context.applicationContext, q) } catch (e: Exception) { "error: ${e.javaClass.simpleName}" } // l10n: not text (a debug check)
            pending.resultCode = if (out.startsWith("ok")) 1 else 2
            pending.resultData = out
            pending.finish()
        }.start()
    }

    private fun check(context: Context, q: String): String {
        val src = File(context.getExternalFilesDir(null), "wiki").listFiles { f -> f.isFile && f.name.endsWith(".zim") }
            ?.maxByOrNull { it.lastModified() } ?: return "no .zim file in Android/data/${context.packageName}/files/wiki/" // l10n: not text (a debug check)
        val dir = File(context.filesDir, "wiki").apply { mkdirs() }
        val zim = File(dir, src.name)
        if (!zim.isFile || zim.length() != src.length()) src.copyTo(zim, overwrite = true)
        val t0 = SystemClock.elapsedRealtime()
        val link = WikiLink.connect(context) ?: return "the :wiki process didn't answer" // l10n: not text (a debug check)
        link.use {
            val info = it.open(zim) ?: return "open failed, status ${it.lastStatus}" // l10n: not text (a debug check)
            val t1 = SystemClock.elapsedRealtime()
            val titles = it.titles(q)
            val t2 = SystemClock.elapsedRealtime()
            val search = it.search(q)
            val t3 = SystemClock.elapsedRealtime()
            val first = titles.firstOrNull() ?: search.firstOrNull()
            val article = first?.let { h -> it.article(h.path) }
            val found = titles.any { h -> h.title.equals(q, ignoreCase = true) } || search.any { h -> h.title.equals(q, ignoreCase = true) }
            return (if (found && article != null) "ok" else "not found") + // l10n: not text (a debug check)
                "; ${zim.name}: ${info.articles} articles, title index ${info.titleIndex}, full text ${info.fullText}, ${info.title}, ${info.language}, ${info.date}" + // l10n: not text (a debug check)
                "; open ${t1 - t0} ms; titles (${t2 - t1} ms): ${titles.joinToString(" | ") { h -> h.title }}" + // l10n: not text (a debug check)
                "; full text (${t3 - t2} ms): ${search.joinToString(" | ") { h -> h.title }}" + // l10n: not text (a debug check)
                "; article: ${article?.title} ${article?.html?.length ?: 0} chars" // l10n: not text (a debug check)
        }
    }
}

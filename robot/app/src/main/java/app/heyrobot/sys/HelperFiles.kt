// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import app.heyrobot.core.AppHandOff
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.ClaudeSpend
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperFacts
import app.heyrobot.core.helpers.HelperSpend
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.time.LocalDate

// Main and voice processes. The helpers' files (0.6.0; Ask Claude's before) and the Claude app's intents, without any API
// key: keys are encrypted and decrypted in the :voice process only (assistant/HelperKeys.kt, through sys/KeystoreSecret.kt,
// the class the updater's token shares). The main app reads whether a key is saved and its masked form, and can delete it
// (then tells the voice process); it never reads a key itself.

/**
 * Where a helper's key lives: files/robot/helpers/<id>/key.bin (IV and ciphertext, :voice only) and key.json (the masked
 * form and when it was saved), encrypted with the Android Keystore key robot_helper_<id>. Claude (id "claude") keeps
 * the files and key from before 0.6.0 (claude_key.bin, claude_key.json, robot_claude_api_key): nothing moved, nothing
 * was encrypted again. Each is a [KeystoreSecret] in that format (0.3.0's), logged as helper_key: a read that fails is
 * removed only when it can never work again, and kept when it may work in a moment.
 */
object HelperKeyFiles {
    /** Claude's Keystore key since 0.3.0 (stored name kept; renaming it would lose the saved key). */
    const val CLAUDE_ALIAS = "robot_claude_api_key"
    /** The log lines' name for every helper's key (helper_key, helper_key_save, helper_key_read, ...), as in 0.6.0. */
    const val LOG = "helper_key"
    private val SAFE = Regex("[a-z0-9_]{1,16}")

    fun alias(id: String): String = if (id == HelperConfig.CLAUDE_ID) CLAUDE_ALIAS else "robot_helper_$id"

    /** The helper's own folder (not Claude's: its files sit where they always were). */
    fun dir(ctx: Context, id: String): File {
        require(SAFE.matches(id)) { "helper id" }
        return File(File(Paths.dir(ctx), "helpers"), id)
    }

    /** Claude's key: files/robot/claude_key.bin and .json, as since 0.3.0 (stored names kept; renaming would lose the key). */
    private val claude = KeystoreSecret(CLAUDE_ALIAS, "claude_key", LOG)

    /**
     * Helper [id]'s key. Only HelperKeys (the :voice process) reads, saves or clears the clipboard through it
     * (ProcessIsolationTest); the main app uses the functions below.
     */
    fun store(id: String): KeystoreSecret = if (id == HelperConfig.CLAUDE_ID) claude else {
        require(SAFE.matches(id)) { "helper id" }
        KeystoreSecret(alias(id), "key", LOG) { dir(it, id) }
    }

    /** IV and ciphertext, written and read only by the :voice process. */
    fun secret(ctx: Context, id: String): File = store(id).secret(ctx)

    /** Not secret: the masked form ("sk-ant-…AB12") and when it was saved, for Settings. */
    fun meta(ctx: Context, id: String): File = store(id).meta(ctx)

    fun saved(ctx: Context, id: String): Boolean = store(id).saved(ctx)

    fun masked(ctx: Context, id: String): String? = store(id).masked(ctx)

    /** A saved key could never be decrypted again and was removed (Settings says so until a new key is saved). */
    fun unreadable(ctx: Context, id: String): Boolean = store(id).unreadable(ctx)

    /**
     * "Delete key" (background thread): the files and the Keystore key go. The caller then tells the voice process
     * ([Cmd.HELPER_KEY]), which drops its client.
     */
    fun delete(ctx: Context, id: String) = store(id).delete(ctx)

    /** "Remove helper": its key (crypto-shredded with the Keystore key), what Test connection found, its folder. */
    fun removeAll(ctx: Context, id: String) {
        delete(ctx, id)
        if (id != HelperConfig.CLAUDE_ID) dir(ctx, id).deleteRecursively()
        HelperFactsStore.file(ctx, id).delete()
    }
}

/** What Test connection found about each helper, and the extras its server refused (core HelperFacts). */
object HelperFactsStore {
    fun file(ctx: Context, id: String): File =
        // Claude's file keeps its 0.x name; renaming it would lose what Test connection learned.
        if (id == HelperConfig.CLAUDE_ID) File(Paths.dir(ctx), "claude_facts.json") else File(HelperKeyFiles.dir(ctx, id), "facts.json")

    fun load(ctx: Context, id: String): HelperFacts = HelperFacts.fromJson(readOrNull(file(ctx, id)))

    /** [VOICE] Background thread. */
    @Synchronized
    fun update(ctx: Context, id: String, f: (HelperFacts) -> HelperFacts) {
        val next = f(load(ctx, id))
        try {
            file(ctx, id).parentFile?.mkdirs()
            writeAtomic(file(ctx, id), next.toJson().toString())
        } catch (t: Throwable) { RLog.w("helper_facts_save", t) }
    }
}

/**
 * Today's spend on helpers (0.6.0; Claude's alone before): written by the :voice process as each request's usage arrives
 * (so a crash mid-answer loses none of it), shown in Settings. The :voice process keeps its copy in memory once read.
 * helpers_spend.json holds the total and each helper's part; without it, 0.4/0.5's claude_spend.json is Claude's spend.
 * Claude's part is still written to claude_spend.json for one release, so a downgrade keeps today's Claude spend.
 */
object HelperSpendStore {
    private fun file(ctx: Context) = File(Paths.dir(ctx), "helpers_spend.json")
    /** Stored name kept from 0.4: older versions read today's Claude spend from it. */
    private fun legacy(ctx: Context) = File(Paths.dir(ctx), "claude_spend.json")

    /** [VOICE] The copy in memory, once read. */
    @Volatile private var memo: HelperSpend? = null

    /** From the files (Settings, in the main process). Background thread. */
    fun load(ctx: Context): HelperSpend = HelperSpend.fromJson(readOrNull(file(ctx)), readOrNull(legacy(ctx)))

    /** Today's spend on all helpers, and each one's (Settings). */
    fun today(ctx: Context): HelperSpend = load(ctx).on(LocalDate.now())

    /** [VOICE] Today's spend, from memory, or from the files the first time. Background thread. */
    fun current(ctx: Context): HelperSpend = memo ?: load(ctx).also { memo = it }

    /** [VOICE] Null until [current] has read the files. */
    fun cached(): HelperSpend? = memo

    /** [VOICE] Background thread. */
    @Synchronized
    fun add(ctx: Context, helper: String, dollars: Double): HelperSpend {
        val today = LocalDate.now()
        val s = current(ctx).add(today, helper, dollars)
        memo = s
        try {
            writeAtomic(file(ctx), s.toJson())
            if (helper == HelperConfig.CLAUDE_ID) writeAtomic(legacy(ctx), ClaudeSpend(s.day, s.of(today, helper)).toJson())
        } catch (t: Throwable) { RLog.w("helper_spend_save", t) }
        return s
    }
}

/**
 * What the Assistant setup screen shows about a helper's app (the Claude app before 1.0.0): installed, and which hand-off
 * resolves (a, b only for the Claude app; c, a share, for every app).
 */
data class AppFacts(val installed: Boolean, val appLink: Boolean, val webLink: Boolean, val send: Boolean)

/**
 * 1.0.0: any helper's app, by package ([AppHandOff]): the hand-off intents and which of them resolve to its package on
 * this phone, checked at run time (queryIntentActivities with setPackage). The apps are closed source and their intent
 * filters unverified. Robot only prefills; you press Send. Never accessibility, never reading an app in the background.
 */
object HandOffIntents {
    fun installed(ctx: Context, pkg: String): Boolean = HandOffApps.installed(ctx, pkg)

    fun intent(ctx: Context, pkg: String, way: AppHandOff.Way, text: String): Intent? = when (way) {
        // The links are the Claude app's own (claude://, claude.ai): no other app is sent them.
        AppHandOff.Way.APP_LINK -> if (pkg != ClaudeAppLink.PACKAGE) null else Intent(Intent.ACTION_VIEW, Uri.parse(ClaudeAppLink.appLink(text))).setPackage(pkg)
        AppHandOff.Way.WEB_LINK -> if (pkg != ClaudeAppLink.PACKAGE) null
            else Intent(Intent.ACTION_VIEW, Uri.parse(ClaudeAppLink.webLink(text))).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
        AppHandOff.Way.SEND -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, AppHandOff.cap(text)).setPackage(pkg)
        AppHandOff.Way.LAUNCH -> ctx.packageManager.getLaunchIntentForPackage(pkg)
    }

    fun resolves(ctx: Context, pkg: String, way: AppHandOff.Way): Boolean = try {
        val i = intent(ctx, pkg, way, "test") ?: return false
        ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo?.packageName == pkg }
    } catch (_: Exception) { false }

    fun facts(ctx: Context, pkg: String): AppFacts {
        val installed = installed(ctx, pkg)
        val claude = pkg == ClaudeAppLink.PACKAGE
        return AppFacts(installed, installed && claude && resolves(ctx, pkg, AppHandOff.Way.APP_LINK),
            installed && claude && resolves(ctx, pkg, AppHandOff.Way.WEB_LINK), installed && resolves(ctx, pkg, AppHandOff.Way.SEND))
    }

    /** The way to hand off to [pkg] now, or null when it isn't installed. */
    fun choose(ctx: Context, pkg: String): AppHandOff.Way? = AppHandOff.choose(pkg, installed(ctx, pkg)) { resolves(ctx, pkg, it) }

    /**
     * The question on the clipboard (your own words, so not marked sensitive) before the app opens: always, so it can be
     * pasted when the app ignores the share. [app]: the app's name, as the clipboard's label.
     */
    fun copy(ctx: Context, text: String, app: String) {
        try {
            val clip = ClipData.newPlainText("Question for $app", text)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        } catch (t: Throwable) {
            RLog.w("handoff_clip", t)
        }
    }
}

/** The Claude app, as [HandOffIntents] with its package (the Claude-only callers from before 1.0.0). */
object ClaudeAppIntents {
    fun installed(ctx: Context): Boolean = HandOffIntents.installed(ctx, ClaudeAppLink.PACKAGE)

    fun resolves(ctx: Context, way: AppHandOff.Way): Boolean = HandOffIntents.resolves(ctx, ClaudeAppLink.PACKAGE, way)

    fun facts(ctx: Context): AppFacts = HandOffIntents.facts(ctx, ClaudeAppLink.PACKAGE)
}

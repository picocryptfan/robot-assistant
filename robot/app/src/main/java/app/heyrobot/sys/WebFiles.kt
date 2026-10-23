// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.PhraseWire
import app.heyrobot.core.readOrNull
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebConsentAnswer
import app.heyrobot.core.web.WebCount
import app.heyrobot.core.web.WebSettings
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

// Main and voice processes. Web search's files (0.9, design A5), each with one writer, none holding a key or a word you
// searched for:
// - web.json, Settings › Web search (core WebSettings): the main app writes it and tells the voice process; settings.json
//   and its schema don't change. No file is web search Off.
// - web_answer.json, the first search's lasting answer (core WebConsentAnswer): the voice process writes it.
// - web_count.json, today's number of searches (core WebCount): the voice process writes it, for the daily cap.
// - web_test.json, what the last Test said (the service and its line, never an address or a key): the voice process's
//   set-up window writes it.
// - search keys (Brave, Kagi, Mojeek): each a KeystoreSecret in files/robot/web/, encrypted and decrypted in the voice
//   process only (assistant WebKeys); the main app reads whether one is saved and its masked form, and may delete it.

object WebStore {
    /** [MAIN] What Settings shows now (Compose reads it; a save changes it at once). */
    val state: MutableState<WebSettings> = mutableStateOf(WebSettings())
    @Volatile private var loaded = false

    fun file(ctx: Context) = File(Paths.dir(ctx), "web.json")

    /** From the file, every time (the voice process reads it at each question: the main app may have changed it). */
    fun load(ctx: Context): WebSettings = WebSettings.fromJson(readOrNull(file(ctx))?.let { try { JSONObject(it) } catch (_: Exception) { null } })

    /** [MAIN] The settings, read from the file the first time this process asks. */
    fun current(ctx: Context): WebSettings {
        if (!loaded) {
            state.value = load(ctx)
            loaded = true
        }
        return state.value
    }

    /** [MAIN] Shows [s] at once, saves it on Settings' writer thread (after any earlier save), then tells the voice process. */
    fun save(ctx: Context, s: WebSettings) {
        state.value = s
        loaded = true
        val app = ctx.applicationContext
        SettingsStore.afterSaves {
            try { writeAtomic(file(app), s.toJson().toString(1)) } catch (t: Throwable) { RLog.w("web_settings_save", t) }
            Cmd.send(app, Cmd.SETTINGS)
        }
    }
}

/** The first search's card's lasting answer: "Allow always", or "No" with "Don't ask again". [VOICE] writes it. */
object WebAnswers {
    fun file(ctx: Context) = File(Paths.dir(ctx), WebConsentAnswer.FILE)

    fun load(ctx: Context): WebConsentAnswer? = WebConsentAnswer.fromJson(readOrNull(file(ctx)))

    fun save(ctx: Context, a: WebConsentAnswer) {
        try { writeAtomic(file(ctx), a.toJson()) } catch (t: Throwable) { RLog.w("web_answer_save", t) }
    }
}

/** Today's searches, for the daily cap and Settings. [VOICE] writes it. */
object WebCounts {
    fun file(ctx: Context) = File(Paths.dir(ctx), WebCount.FILE)

    fun load(ctx: Context): WebCount = WebCount.fromJson(readOrNull(file(ctx)))

    fun today(ctx: Context): Int = load(ctx).on(LocalDate.now())

    @Synchronized
    fun add(ctx: Context) {
        try { writeAtomic(file(ctx), load(ctx).add(LocalDate.now()).toJson()) } catch (t: Throwable) { RLog.w("web_count_save", t) }
    }
}

/**
 * What the last Test found, for Settings: the service, whether it worked, its line ("JSON on · 9 results · …"), when. 1.1: the
 * line is kept without a language (PhraseWire) so Settings shows it in the menus' language; an older record's plain English
 * reads as it is.
 */
data class WebTest(val service: SearchService, val ok: Boolean, val line: Phrase, val at: Long) {
    constructor(service: SearchService, ok: Boolean, line: String, at: Long) : this(service, ok, Phrase.plain(line), at)

    fun toJson(): String = JSONObject().put("service", service.name).put("ok", ok).put("line", PhraseWire.encode(line)).put("at", at).toString()

    companion object {
        fun file(ctx: Context) = File(Paths.dir(ctx), "web_test.json")

        fun load(ctx: Context): WebTest? = try {
            val o = JSONObject(readOrNull(file(ctx)) ?: return null)
            WebTest(SearchService.valueOf(o.getString("service")), o.optBoolean("ok"), o.optString("line").let { PhraseWire.decode(it) ?: Phrase.plain(it) }, o.optLong("at"))
        } catch (_: Exception) {
            null
        }

        /** [VOICE] The set-up window, after Test. */
        fun save(ctx: Context, t: WebTest) {
            try { writeAtomic(file(ctx), t.toJson()) } catch (e: Throwable) { RLog.w("web_test_save", e) }
        }
    }
}

/**
 * The search services' keys: files/robot/web/search_brave.bin (IV and ciphertext, :voice only) and .json (the masked form),
 * each with its own Android Keystore key robot_search_<service>. Only WebKeys (the :voice process) reads or saves one
 * (ProcessIsolationTest); the main app asks whether one is saved, its masked form, and deletes it.
 */
object WebKeyFiles {
    const val LOG = "search_key"

    fun dir(ctx: Context) = File(Paths.dir(ctx), "web")

    fun store(service: SearchService): KeystoreSecret {
        val id = requireNotNull(service.keyId) { "no key" } // l10n: not text (a developer's error)
        return KeystoreSecret("robot_$id", id, LOG) { dir(it) } // l10n: not text (a Keystore alias)
    }

    fun saved(ctx: Context, service: SearchService): Boolean = service.keyId != null && store(service).saved(ctx)

    fun masked(ctx: Context, service: SearchService): String? = if (service.keyId == null) null else store(service).masked(ctx)

    /** Delete key (background thread): the files and the Keystore key go. */
    fun delete(ctx: Context, service: SearchService) {
        if (service.keyId != null) store(service).delete(ctx)
    }
}

/** The web search set-up window (assistant WebSearchActivity, in :voice where keys are decrypted), opened by class name. */
object WebWindow {
    const val CLASS = "app.heyrobot.assistant.WebSearchActivity"
    const val EXTRA_MODE = "mode"
    /** Paste the chosen service's key, then Test. */
    const val MODE_KEY = "key"
    /** Test only. */
    const val MODE_TEST = "test"
}

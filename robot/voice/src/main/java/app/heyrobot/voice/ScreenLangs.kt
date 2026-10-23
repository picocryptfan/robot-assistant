// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.app.LocaleManager
import android.content.Context
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase

// :voice. 1.1: the menus' languages in this process, for what the voice engines hand the panel as its own notices (voice
// input's hints and failures): Android gives every process of Robot its per-app language, so the process's configuration
// says the menus' (as the app's sys/AppLanguage reads it). The voice module can't see the app's code, hence this small twin.

internal object ScreenLangs {
    fun of(ctx: Context): Langs = try {
        val first = ctx.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)
        val ui = Lang.of(first?.language)
        val system = try {
            Lang.of(ctx.getSystemService(LocaleManager::class.java)?.systemLocales?.takeIf { !it.isEmpty }?.get(0)?.language ?: first?.language)
        } catch (_: Throwable) { ui }
        Langs(ui, ui, system)
    } catch (_: Throwable) {
        Langs.EN
    }

    /** [p] in the menus' language. */
    fun shown(ctx: Context, p: Phrase): String = p.render(of(ctx))
}

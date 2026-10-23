// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.LocaleList
import android.provider.Settings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Pseudo

// Main and voice processes. Robot's menus' language (1.1): Android's per-app language, through the
// framework's LocaleManager (Android 13; Robot's minSdk is 34, so no AppCompat). Android keeps the choice, shows it in
// Settings › Apps › Robot › Language too, recreates Robot's activities when it changes, and applies it to every process of
// the app, :voice's panel included (AOSP, VERIFIED in the design; the phone check P2). The languages offered are the
// ones AGP's generateLocaleConfig writes from the resources: en-US and fr-CA.

/** Settings › Language › Menus. [tag]: what Android stores (null: the phone's language). */
enum class Menus(val tag: String?) {
    PHONE(null), EN("en-US"), FR("fr-CA");
}

object AppLanguage {
    private fun manager(ctx: Context): LocaleManager? = try { ctx.getSystemService(LocaleManager::class.java) } catch (_: Throwable) { null }

    /** The menus' language Android has for Robot. */
    fun menus(ctx: Context): Menus {
        val l = try { manager(ctx)?.applicationLocales } catch (_: Throwable) { null }
        if (l == null || l.isEmpty) return Menus.PHONE
        return when (l[0].language) {
            "fr" -> Menus.FR
            "en" -> Menus.EN
            else -> Menus.PHONE
        }
    }

    /** Sets the menus' language; Android recreates Robot's activities in it. */
    fun setMenus(ctx: Context, m: Menus) {
        try {
            manager(ctx)?.applicationLocales = m.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } catch (_: Throwable) {}
    }

    /** The phone's own languages (not Robot's): an Android screen named in a sentence is in these. */
    fun systemLocales(ctx: Context): LocaleList =
        (try { manager(ctx)?.systemLocales } catch (_: Throwable) { null })?.takeIf { !it.isEmpty } ?: Resources.getSystem().configuration.locales

    /**
     * The languages a screen renders core's words in (core i18n Langs): Robot's screens' from [config] (what the resources
     * resolved to; a German phone gets the English resources, so English), Android's from the phone's, and the pseudo-locale
     * when the menus are en-XA or ar-XB (debug builds). Outside a turn, a sentence is in the screens' language.
     */
    fun langs(ctx: Context, config: Configuration = ctx.resources.configuration): Langs {
        val first = config.locales.takeIf { !it.isEmpty }?.get(0)
        val ui = Lang.of(first?.language)
        val system = Lang.of(systemLocales(ctx).takeIf { !it.isEmpty }?.get(0)?.language)
        return Langs(say = ui, ui = ui, system = system, pseudo = Pseudo.of(first?.toLanguageTag()))
    }

    /** Android's language page for Robot (Settings › Apps › Robot › Language). */
    fun androidPage(ctx: Context): Intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

    /** Opens [androidPage]; false when nothing did. */
    fun openAndroidPage(ctx: Context): Boolean = try {
        ctx.startActivity(androidPage(ctx).apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

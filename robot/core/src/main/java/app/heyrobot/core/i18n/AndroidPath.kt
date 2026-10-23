// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Pure. Android's own screens that Robot's sentences name, in Android's words, rendered in the phone's
// language ([Langs.system]), never Robot's: Android's Settings app is « Paramètres », Robot's are « Réglages ».
// The English is GrapheneOS's (Android 16 and 17, as Robot's sentences name them today). The French is AOSP's fr-CA
// words as far as they could be read from the build box: UNVERIFIED on a GrapheneOS phone in français (Canada). The README's "On the phone" steps copy them from the phone; a word that differs is fixed here.

enum class AndroidPath(private val en: String, private val fr: String, val partial: Boolean = false) {
    SETTINGS("Settings", "Paramètres"),
    APPS("Settings › Apps", "Paramètres › Applications"),
    APP_INFO("Settings › Apps › Robot", "Paramètres › Applications › Robot"),
    APP_PERMISSIONS("Settings › Apps › Robot › Permissions", "Paramètres › Applications › Robot › Autorisations"),
    APP_NOTIFICATIONS("Settings › Apps › Robot › Notifications", "Paramètres › Applications › Robot › Notifications"),
    APP_LANGUAGE("Settings › Apps › Robot › Language", "Paramètres › Applications › Robot › Langue"),
    DEFAULT_APPS("Settings › Apps › Default apps", "Paramètres › Applications › Applications par défaut"),
    DIGITAL_ASSISTANT("Settings › Apps › Default apps › Digital assistant app",
        "Paramètres › Applications › Applications par défaut › Application d'assistant numérique"),
    SPECIAL_ACCESS("Settings › Apps › Special app access", "Paramètres › Applications › Accès spécial des applications"),
    INSTALL_UNKNOWN_APPS("Settings › Apps › Special app access › Install unknown apps",
        "Paramètres › Applications › Accès spécial des applications › Installer des applis inconnues"),
    USAGE_ACCESS("Settings › Apps › Special app access › Usage access",
        "Paramètres › Applications › Accès spécial des applications › Accès aux données d'utilisation"),
    NOTIFICATION_ACCESS("Settings › Notifications › Device & app notifications",
        "Paramètres › Notifications › Notifications des appareils et des applis"),
    ABOUT_PHONE("Settings › About phone", "Paramètres › À propos du téléphone"),
    LANGUAGES("Settings › System › Languages", "Paramètres › Système › Langues"),
    DEVELOPER_OPTIONS("Settings › System › Developer options", "Paramètres › Système › Options pour les développeurs"),
    /** The row to tap seven times in About phone: just its name, the sentence names About phone first ([partial]). */
    BUILD_NUMBER("Build number", "Numéro de build", partial = true),
    /** Rows of Developer options: just their names, the sentence names Developer options first ([partial]). */
    USB_DEBUGGING("USB debugging", "Débogage USB", partial = true),
    WIRELESS_DEBUGGING("Wireless debugging", "Débogage sans fil", partial = true),
    /** Where another app's own page keeps a grant it no longer asks for (Termux's): its App info page, not a path from Settings ([partial]). */
    APP_ADDITIONAL_PERMISSIONS("App info › Permissions › Additional permissions",
        "Infos sur l'appli › Autorisations › Autorisations supplémentaires", partial = true),
    SPECIAL_NOTIFICATION_ACCESS("Settings › Apps › Special app access › Notification access",
        "Paramètres › Applications › Accès spécial des applications › Accès aux notifications");

    /** In [lang], as Android writes it; aloud, "›" is a pause. [partial] ones are a part of a path, which the sentence starts. */
    fun render(lang: Lang, medium: Medium = Medium.SCREEN): String {
        val s = if (lang == Lang.FR) FrTypo.fix(fr) else en
        return if (medium == Medium.VOICE) VoiceForm.clean(s, lang) else s
    }

    /** Both languages' words, for the tests. */
    val texts: List<String> get() = listOf(en, fr)
}

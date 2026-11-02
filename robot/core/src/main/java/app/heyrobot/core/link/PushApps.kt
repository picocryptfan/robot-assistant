// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

/** A push app on this phone: its package, and the name you'll see before choosing it. */
data class PushApp(val pkg: String, val label: String)

/**
 * The Push app row (F27): the apps that offer to carry push, the one chosen (null: none), and how many of your active
 * pairings have no push address yet.
 */
class PushApps(val chosen: String?, val waiting: Int, val apps: List<PushApp>) {
    companion object {
        const val MAX_APPS = 16
        const val MAX_PACKAGE = 255
        private const val MAX_LABEL = 60
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") // l10n: not text (a package name's form)

        fun packageOk(s: String): Boolean = s.length <= MAX_PACKAGE && PACKAGE.matches(s)

        /** Another app chose this name: one line, no control or direction marks, short; its package when nothing's left. */
        fun label(raw: CharSequence?, pkg: String): String {
            val s = raw?.toString().orEmpty().filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }.trim().take(MAX_LABEL).trim()
            return s.ifEmpty { pkg }
        }
    }
}

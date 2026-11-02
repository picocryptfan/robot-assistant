// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

/**
 * Settings › Voice › "Wait before answering" (1.1, settings.json "listenPause"; unknown: [LONG], a new install's default since 31 Oct 2026; absent from a file: Normal, RobotSettings.fromJson): the pause
 * you can make mid-question without Robot taking it as the end. :voice's Endpointer turns it into its window.
 */
enum class ListenPause(val id: String, val ms: Int) {
    SHORT("short", 600),
    NORMAL("normal", 1_000),
    LONG("long", 1_600),
    VERY_LONG("very_long", 2_500);

    companion object {
        /** A saved id; unknown: [LONG]. */
        fun of(id: String?): ListenPause = entries.firstOrNull { it.id == id } ?: LONG
    }
}

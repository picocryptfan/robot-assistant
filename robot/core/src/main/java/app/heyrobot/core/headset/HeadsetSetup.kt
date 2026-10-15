// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.RememberedDevice

// Pure. What Assistant setup and Settings › Headset say about the headset button (design §3.1 "What decides the target",
// §3.4): which app answers each of the three intents now (PackageManager.resolveActivity with MATCH_DEFAULT_ONLY, Robot
// seeing the other handlers through its <queries> for these three actions), and the rows of "Your headphones".

/** Who answers one of the three intents now. */
enum class ButtonHandler {
    /** Robot's HeadsetEntryActivity. */
    ROBOT,
    /** Android's chooser: several apps answer and none was picked with Always. */
    ASK,
    /** Another app answers it. */
    OTHER,
    /** Nobody (Robot's activity turned off and no other handler). */
    NONE,
}

/** Assistant setup's "Headset button" row. [ok]: true all Robot's, false something to do, null nothing to do (turned off). */
data class ButtonRow(val title: String, val text: String, val ok: Boolean?)

object HeadsetSetup {
    const val TITLE = "Headset button"

    /** The handler from what resolveActivity returned ([pkg] and [cls] of its activity; null: nothing resolves). */
    fun handler(pkg: String?, cls: String?, own: String): ButtonHandler = when {
        pkg == null -> ButtonHandler.NONE
        pkg == own -> ButtonHandler.ROBOT
        // The chooser: the framework's ResolverActivity (package "android"), or a platform's own chooser activity.
        pkg == "android" || cls?.endsWith("ResolverActivity") == true || cls?.endsWith("ChooserActivity") == true -> ButtonHandler.ASK
        else -> ButtonHandler.OTHER
    }

    /**
     * "Headset button → Robot ✓", or "→ ask each time: pick Robot and Always", or "→ another app", from the three
     * intents' handlers ([handlers] by action: any missing reads as unknown). [buttonOn]: "Headset button opens Robot".
     */
    fun row(buttonOn: Boolean, handlers: Map<String, ButtonHandler>): ButtonRow {
        if (!buttonOn) return ButtonRow(TITLE, "Off: other apps get the headset's button (Settings › Headset)", null)
        val h = HeadsetEntry.ACTIONS.map { handlers[it] }
        return when {
            h.all { it == ButtonHandler.ROBOT } -> ButtonRow(TITLE, "Headset button → Robot ✓", true)
            h.any { it == ButtonHandler.ASK } -> ButtonRow(TITLE, "Headset button → ask each time: press it, pick Robot and Always", false)
            h.any { it == ButtonHandler.OTHER } -> ButtonRow(TITLE, "Headset button → another app answers it: pick Robot in Android's settings, or turn that app off", false)
            else -> ButtonRow(TITLE, "Headset button → not known yet", false)
        }
    }

    /** "Pixel Buds Pro · private" for Settings › Headset › Your headphones. */
    fun deviceLine(d: RememberedDevice): String = when (d.private) {
        true -> "Private: only you hear it"
        false -> "Not private: personal answers stay on screen"
        null -> "Not asked yet: Robot asks the first time a personal answer would play"
    }

    /** The headphones Settings lists: remembered headsets, newest seen first. */
    fun headphones(memory: DeviceMemory): List<RememberedDevice> = memory.headsets.sortedByDescending { it.seen }
}

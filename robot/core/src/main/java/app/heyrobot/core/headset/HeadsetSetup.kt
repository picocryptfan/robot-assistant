// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.RememberedDevice

// Pure. What Assistant setup and Settings › Headset say about the headset button: which app answers each of the three intents now (PackageManager.resolveActivity with MATCH_DEFAULT_ONLY, Robot
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
data class ButtonRow(val title: String, val text: String, val ok: Boolean?) {
    /** 1.1: [title] and [text] without a language yet (Assistant setup shows them in the menus' language); not part of equality. */
    var titleText: Phrase = Phrase.plain(title)
        private set
    var textPhrase: Phrase = Phrase.plain(text)
        private set

    constructor(title: Phrase, text: Phrase, ok: Boolean?) : this(title.en(), text.en(), ok) {
        titleText = title
        textPhrase = text
    }
}

object HeadsetSetup {
    val TITLE: String get() = HandsFreeStrings.buttonTitle().en()

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
        val title = HandsFreeStrings.buttonTitle()
        if (!buttonOn) return ButtonRow(title, HandsFreeStrings.buttonOff("page" to UiStrings.inSettings(UiStrings.headset)), null)
        val h = HeadsetEntry.ACTIONS.map { handlers[it] }
        return when {
            h.all { it == ButtonHandler.ROBOT } -> ButtonRow(title, HandsFreeStrings.buttonRobot(), true)
            h.any { it == ButtonHandler.ASK } -> ButtonRow(title, HandsFreeStrings.buttonAsk(), false)
            h.any { it == ButtonHandler.OTHER } -> ButtonRow(title, HandsFreeStrings.buttonOther(), false)
            else -> ButtonRow(title, HandsFreeStrings.buttonUnknown(), false)
        }
    }

    /** "Pixel Buds Pro · private" for Settings › Headset › Your headphones. */
    fun deviceLine(d: RememberedDevice): String = deviceLineText(d).en()

    /** 1.1: [deviceLine] without a language yet. */
    fun deviceLineText(d: RememberedDevice): Phrase = when (d.private) {
        true -> HandsFreeStrings.devicePrivate()
        false -> HandsFreeStrings.deviceNotPrivate()
        null -> HandsFreeStrings.deviceNotAsked()
    }

    /** The headphones Settings lists: remembered headsets, newest seen first. */
    fun headphones(memory: DeviceMemory): List<RememberedDevice> = memory.headsets.sortedByDescending { it.seen }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. The empty panel's tips (0.4.0): three things Robot can do, a different three each time the panel opens, from
// the features that are on and can work right now (an action turned Off, texting off, the lock screen, no helper, no
// screen this time: not offered). A question is asked at once, as if typed; a tip that would do something (an alarm, a
// note, the flashlight, a text) or go online (the weather, the nearest place, a helper) only fills the text field: its
// details are an example, and nothing happens until you send it.

/**
 * [ASK]: tapping asks [Tip.text] (a question that only reads on the phone). [FILL]: tapping puts it in the text field, to
 * change or send. [SCREEN]: attaches the screen, then asks it (the local model only). [PHOTO]: opens the camera and photos row.
 */
enum class TipKind { ASK, FILL, SCREEN, PHOTO }

data class Tip(val text: String, val kind: TipKind = TipKind.ASK)

object Tips {
    /** Shown only when the screen came with this panel (it may come a moment after the panel opens: [withScreen]). */
    val SCREEN = Tip("What's on my screen?", TipKind.SCREEN)

    /**
     * What the panel can do right now. [claude]: the Claude helper is on and reachable (the helper tip's condition, as
     * before 0.6.0); [screen]: the screen came with this panel.
     */
    data class Can(val settings: RobotSettings, val locked: Boolean, val helper: Boolean = false, val screen: Boolean = false)

    /**
     * [tool]: what tapping it would do; [online]: it goes online without a tool of its own (a helper). One that writes,
     * leaves the phone or goes online only fills the text field ([TipKind.FILL]).
     */
    private class Entry(tip: Tip, val tool: ToolName? = null, online: Boolean = false, val needs: (Can) -> Boolean = { true }) {
        val tip: Tip = if (tip.kind == TipKind.ASK && (online || (tool != null && (!tool.reads || tool.online)))) tip.copy(kind = TipKind.FILL) else tip
    }

    /** In the order they rotate through, mixing kinds so any three show different sides of Robot. */
    private val ALL: List<Entry> = listOf(
        Entry(Tip("What's the weather tomorrow?"), ToolName.WEATHER),
        Entry(Tip("Set a timer for 10 minutes"), ToolName.SET_TIMER),
        Entry(Tip("Text Mom I'm on my way"), ToolName.SEND_TEXT, needs = { it.settings.textApp != TextApp.OFF }),
        Entry(Tip("What's on my calendar today?"), ToolName.READ_CALENDAR),
        Entry(Tip("Ask about a photo", TipKind.PHOTO), needs = { !it.locked }),
        Entry(Tip("Remember that I parked on level 3"), ToolName.ADD_NOTE),
        Entry(Tip("How's my battery?"), ToolName.PHONE_STATUS),
        Entry(Tip("Ask a helper why the sky is blue"), online = true, needs = { it.helper }),
        Entry(Tip("Directions to the nearest pharmacy"), ToolName.MAPS_NAVIGATE),
        Entry(SCREEN, needs = { it.screen }),
        Entry(Tip("Wake me up at 7:00"), ToolName.SET_ALARM),
        Entry(Tip("Help me free up space"), ToolName.FREE_STORAGE),
        Entry(Tip("Email Sam about dinner on Friday"), ToolName.DRAFT_EMAIL),
        Entry(Tip("Find my note about the wifi"), ToolName.FIND_NOTES),
        Entry(Tip("Turn on the flashlight"), ToolName.FLASHLIGHT),
        Entry(Tip("Add lunch with Alex on Friday at noon"), ToolName.ADD_EVENT),
        Entry(Tip("How much storage is free?"), ToolName.PHONE_STATUS),
        Entry(Tip("Tell me a fun fact")),
    )

    /** Every tip that can work now: its action on in Settings (and allowed on the lock screen when locked), and its needs met. */
    fun available(can: Can): List<Tip> = ALL.filter { e ->
        val t = e.tool
        (t == null || (can.settings.approval(t) != Approval.OFF && (!can.locked || t in can.settings.lockedTools))) && e.needs(can)
    }.map { it.tip }

    /**
     * [n] tips for the [round]th panel open: the next [n] of [available] each time, wrapping around, so every tip shows in
     * turn. Fewer when fewer can work.
     */
    fun pick(can: Can, round: Int, n: Int = 3): List<Tip> {
        val all = available(can)
        if (all.size <= n) return all
        val start = Math.floorMod(round.toLong() * n, all.size.toLong()).toInt()
        return (0 until n).map { all[(start + it) % all.size] }
    }

    /** The screen came after the tips were picked: it takes the last one's place (once). */
    fun withScreen(tips: List<Tip>): List<Tip> = if (SCREEN in tips || tips.isEmpty()) tips else tips.dropLast(1) + SCREEN
}

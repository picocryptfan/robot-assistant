// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Pure. Every area's catalog: WP0 made this list complete, one object per area package (and
// one per half of the three that may split), so no package edits this file. Each object starts empty and is filled by
// the package that owns it (§5.2); CatalogsTest renders every entry of every catalog, in both languages, on screen and
// aloud.

object Catalogs {
    val all: List<Strings> = listOf(
        UiStrings,
        // WP1: the turn, the cards, the tool results.
        TurnStrings, ToolStrings, InfoStrings,
        // WP2: the panel and Home (WP2a), the helpers (WP2b).
        PanelStrings, HelperStrings,
        // WP3: messages, memory, people and notes.
        MessageStrings, MemoryStrings, PeopleStrings, ScamStrings,
        // WP4: routines and the brief (WP4a), headset and car (WP4b).
        RoutineStrings, BriefStrings, HandsFreeStrings, TimerStrings,
        // WP5: conversation mode and translation.
        ConversationStrings,
        // WP6: web search and Termux.
        WebStrings, WikiStrings, TermuxStrings, ShotStrings,
        // WP7: the model pages and model state (WP7a), the engines and the voice (WP7b).
        ModelStrings, VoiceStrings,
        // WP8: Settings, the setup guide, updates, feedback.
        SettingsStrings, SetupStrings, UpdateStrings, FeedbackStrings,
        // 1.1: Back up and restore.
        BackupStrings,
        // 1.1: Phone lock.
        LockStrings,
        // 1.1: Skills.
        SkillStrings,
        // 1.4: Robot Files.
        FilesStrings,
        // 1.4: Lists.
        ListsStrings,
        // Correct the last action ("undo that", "no, 6:45").
        CorrectStrings,
        // The everyday set: music, sums, What went online, Ask Robot.
        EverydayStrings,
        // Skills connections.
        ConnStrings,
        // 1.5: Robot to Robot.
        LinkStrings,
        // 1.3: point and read.
        ReadStrings,
        // Ready answers (idea 18).
        ReadyStrings,
        // Caller name (idea 13).
        CallerStrings,
        // When did I last, from everything.
        LastTimeStrings,
        // Spoken log: the logbook.
        LogbookStrings,
    )

    private val byId: Map<String, Msg> by lazy { all.flatMap { it.all }.associateBy { it.id } }

    /** The entry with [id], or null (an id from another version of Robot). */
    fun msg(id: String): Msg? = byId[id]
}

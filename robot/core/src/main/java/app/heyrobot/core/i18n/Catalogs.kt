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
        MessageStrings, MemoryStrings, PeopleStrings,
        // WP4: routines and the brief (WP4a), headset and car (WP4b).
        RoutineStrings, BriefStrings, HandsFreeStrings,
        // WP5: conversation mode and translation.
        ConversationStrings,
        // WP6: web search and Termux.
        WebStrings, TermuxStrings,
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
    )

    private val byId: Map<String, Msg> by lazy { all.flatMap { it.all }.associateBy { it.id } }

    /** The entry with [id], or null (an id from another version of Robot). */
    fun msg(id: String): Msg? = byId[id]
}

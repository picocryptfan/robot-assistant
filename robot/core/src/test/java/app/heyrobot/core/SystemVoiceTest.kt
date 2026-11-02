// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.2: the phone's voice by its engine's own name, chosen the way Android chooses its default engine (AOSP TtsEngines). */
class SystemVoiceTest {
    private val graphene = TtsEngine("app.example.speech", "Speech Services", system = true, priority = 0)
    private val google = TtsEngine("com.example.google.tts", "Speech engine by Example", system = true, priority = 100)
    private val samsung = TtsEngine("com.example.samsung.tts", "Example TTS", system = true, priority = 0)
    private val store = TtsEngine("org.example.rhvoice", "RHVoice", system = false, priority = 900)

    @Test fun theSettingWinsWhenItsEngineIsInstalled() {
        assertEquals(samsung, SystemVoice.default(samsung.pkg, listOf(google, samsung)))
        assertEquals(store, SystemVoice.default(" ${store.pkg} ", listOf(google, store)))
        assertEquals("RHVoice", SystemVoice.label(store.pkg, listOf(google, store)))
    }

    @Test fun unsetOrUninstalledFallsToTheHighestRankedSystemEngine() {
        for (setting in listOf(null, "", "  ", "com.example.gone")) {
            // System engines first, whatever an app engine's priority; among them the higher priority.
            assertEquals(setting.toString(), google, SystemVoice.default(setting, listOf(store, samsung, google)))
            // One system engine (GrapheneOS's case): that one.
            assertEquals(setting.toString(), graphene, SystemVoice.default(setting, listOf(graphene)))
        }
        // Only app engines and no setting: Android has no default either.
        assertNull(SystemVoice.default(null, listOf(store)))
        assertNull(SystemVoice.default(null, emptyList()))
        assertNull(SystemVoice.default(google.pkg, emptyList()))
    }

    @Test fun anEngineWithoutANameReadsAsThePhones() {
        assertNull(SystemVoice.label(null, listOf(graphene.copy(label = null))))
        assertNull(SystemVoice.label(null, listOf(graphene.copy(label = "  "))))
        assertNull(SystemVoice.label(null, emptyList()))
        assertEquals("The phone's text-to-speech engine", SystemVoice.detailText(null).en())
        assertEquals("Le moteur de synthèse vocale du téléphone", SystemVoice.detailText(" ").render(Langs.of(Lang.FR)))
    }

    @Test fun theVoiceListNamesTheEngineInBothLanguages() {
        // The engine's name is the phone's own words: the same in English and French.
        for (l in listOf(Langs.EN, Langs.of(Lang.FR))) {
            assertEquals("Speech Services", VoiceChoice.SYSTEM.detailText("Speech Services").render(l))
            assertEquals("Speech Services", SystemVoice.detailText(SystemVoice.label(null, listOf(graphene))).render(l))
        }
        // The other voices keep their own lines.
        assertEquals(VoiceChoice.HEART.detailText, VoiceChoice.HEART.detailText("Speech Services"))
        assertEquals("English, natural (Kokoro)", VoiceChoice.HEART.detailText("Speech Services").en())
    }

    /**
     * The everyday set, E6 (D16): Robot's own "System voice" never speaks through Robot, now that Robot can be the phone's
     * engine: the default when it's another engine, else the one before Robot, else the highest-ranked other one, else none.
     */
    @Test fun robotsSystemVoiceIsNeverRobot() {
        val robot = TtsEngine("ai.robot_app", "Robot", system = false, priority = 0)
        val own = robot.pkg
        val engines = listOf(robot, graphene, google, store)
        // The default is another engine: that one.
        assertEquals(store, SystemVoice.forRobot(store.pkg, engines, own, before = null))
        // Robot is the default: the one that was before it, while it's installed.
        assertEquals(store, SystemVoice.forRobot(own, engines, own, before = store.pkg))
        assertEquals(google, SystemVoice.forRobot(own, engines, own, before = "com.example.gone"))
        // Else the highest-ranked other engine, system ones first.
        assertEquals(google, SystemVoice.forRobot(own, engines, own, before = null))
        assertEquals(store, SystemVoice.forRobot(own, listOf(robot, store), own, before = null))
        assertEquals(graphene, SystemVoice.forRobot(null, listOf(robot, graphene), own, before = null))
        // Robot alone: none (the System voice is then Heart).
        assertNull(SystemVoice.forRobot(own, listOf(robot), own, before = own))
        assertNull(SystemVoice.forRobot(null, emptyList(), own, before = null))
        assertEquals("Speech engine by Example", SystemVoice.labelForRobot(own, engines, own, null))
        assertNull(SystemVoice.labelForRobot(own, listOf(robot), own, null))
        // What's kept as "before": another installed default only.
        assertEquals(store.pkg, SystemVoice.toRemember(" ${store.pkg} ", engines, own))
        assertNull(SystemVoice.toRemember(own, engines, own))
        assertNull(SystemVoice.toRemember("com.example.gone", engines, own))
        assertNull(SystemVoice.toRemember(null, engines, own))
    }
}

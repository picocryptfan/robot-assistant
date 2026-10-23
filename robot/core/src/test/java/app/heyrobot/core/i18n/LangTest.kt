// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import app.heyrobot.core.RobotSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1's one language type, with the names and ids the brief, hands-free and messages files keep; and the language
 * settings, still settings.json schema 13, which older files read as the defaults.
 */
class LangTest {
    @Test fun oneTypeWithTheSamePersistedForms() {
        assertEquals(listOf("en", "fr"), Lang.entries.map { it.id })
        assertEquals(listOf("EN", "FR"), Lang.entries.map { it.name })
        assertEquals(Lang.FR, Lang.valueOf("FR"))
        for (tag in listOf("fr-CA", "fr", " FR ", "fr-FR", "fr-CH")) assertEquals(tag, Lang.FR, Lang.of(tag))
        for (tag in listOf("en-US", "en", "de-DE", "", null)) assertEquals(tag.toString(), Lang.EN, Lang.of(tag))
        assertEquals(Lang.FR, Lang.ofId("fr"))
        assertEquals(null, Lang.ofId("de"))
    }

    @Test fun theLanguageSettingsStayInSchema13() {
        val d = RobotSettings()
        assertEquals(ReplyLangChoice.SPOKEN, d.replyLang)
        assertFalse(d.menusNoticeSeen)
        val json = d.toJson()
        assertEquals(13, json.getInt("schema"))
        // The defaults aren't written, so a file written now is the same as 1.0's for anyone who doesn't choose.
        assertFalse(json.has("replyLang") || json.has("menusNoticeSeen"))
        // An older file (1.0's) reads as the defaults.
        val old = JSONObject(json.toString()).apply { remove("replyLang"); remove("menusNoticeSeen") }.toString()
        assertEquals(d, RobotSettings.fromJson(old))
        val set = d.copy(replyLang = ReplyLangChoice.FR, menusNoticeSeen = true)
        val saved = set.toJson()
        assertEquals("fr", saved.getString("replyLang"))
        assertTrue(saved.getBoolean("menusNoticeSeen"))
        assertEquals(set, RobotSettings.fromJson(saved.toString()))
        assertEquals(ReplyLangChoice.SPOKEN, RobotSettings.fromJson(saved.put("replyLang", "tlh").toString()).replyLang)
    }
}

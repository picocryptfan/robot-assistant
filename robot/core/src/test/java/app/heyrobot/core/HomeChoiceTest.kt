// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HS2: the home page you picked, in home_settings.json, and 1.3 reading the file back. */
class HomeChoiceTest {
    /** release/1.3's HomeSettings.fromJson, as far as the home page goes: a file of another `v` is the defaults (Getting started). */
    private fun today13(text: String): Boolean = JSONObject(text).let { o -> o.optInt("v", 0) == 1 && o.optBoolean("today", false) }

    @Test fun eachChoiceRoundTripsAndOnlyTodayWritesTodayOn() {
        for (c in HomeChoice.entries) {
            val s = HomeSettings(home = c)
            assertEquals(c, HomeSettings.fromJson(s.toJson()).home)
            assertEquals(c.key, JSONObject(s.toJson()).getString("home"))
            assertEquals(c == HomeChoice.TODAY, JSONObject(s.toJson()).getBoolean("today"))
            assertEquals(c == HomeChoice.TODAY, s.today)
        }
    }

    @Test fun release13ReadsA14FileOntoTheRightPage() {
        assertTrue(today13(HomeSettings(home = HomeChoice.TODAY).toJson()))
        assertFalse(today13(HomeSettings(home = HomeChoice.START).toJson()))
        // 1.3 has no Quick access: it lands on Getting started, its default.
        assertFalse(today13(HomeSettings(home = HomeChoice.QUICK).toJson()))
        assertEquals(1, JSONObject(HomeSettings(home = HomeChoice.QUICK).toJson()).getInt("v"))
    }

    @Test fun aFileWithoutHomeReadsFromToday() {
        assertEquals(HomeChoice.TODAY, HomeSettings.fromJson("""{"v":1,"today":true,"offer":"taken"}""").home)
        assertEquals(HomeChoice.START, HomeSettings.fromJson("""{"v":1,"today":false,"offer":"declined"}""").home)
        assertEquals(HomeChoice.START, HomeSettings.fromJson("""{"v":1}""").home)
        // A name this version doesn't know (a later one's): `today` decides.
        assertEquals(HomeChoice.TODAY, HomeSettings.fromJson("""{"v":1,"home":"later","today":true}""").home)
        assertEquals(HomeChoice.QUICK, HomeSettings.fromJson("""{"v":1,"home":"quick","today":false}""").home)
    }

    @Test fun theOfferShowsOnlyOnGettingStarted() {
        assertTrue(HomeSettings().offerShows(ready = true, guideUnderWay = false))
        assertFalse(HomeSettings(home = HomeChoice.QUICK).offerShows(ready = true, guideUnderWay = false))
        assertFalse(HomeSettings(home = HomeChoice.TODAY).offerShows(ready = true, guideUnderWay = false))
    }
}

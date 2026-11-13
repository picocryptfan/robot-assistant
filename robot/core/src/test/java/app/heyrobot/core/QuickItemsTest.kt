// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** HS2: Quick access's buttons, only what this phone has, "Set up" for what it can download. */
class QuickItemsTest {
    private fun items(p: QuickPhone) = QuickItems.shown(QuickItem.DEFAULT, p).map { it.item }

    @Test fun aBarePhoneShowsTheAlwaysButtonsInThePlansOrder() {
        assertEquals(listOf(QuickItem.TALK, QuickItem.CAR, QuickItem.LISTS, QuickItem.NOTES, QuickItem.FILES, QuickItem.CALENDAR,
            QuickItem.HISTORY, QuickItem.ROUTINES), items(QuickPhone()))
        assertEquals(QuickItem.DEFAULT, items(QuickPhone(link = true, skills = 1, speech = true, translation = true)))
    }

    @Test fun eachFeatureHasItsTest() {
        assertNull(QuickItems.state(QuickItem.LINK, QuickPhone(link = false)))
        assertEquals(QuickState.READY, QuickItems.state(QuickItem.LINK, QuickPhone(link = true)))
        assertNull(QuickItems.state(QuickItem.SKILLS, QuickPhone(skills = 0)))
        assertEquals(QuickState.READY, QuickItems.state(QuickItem.SKILLS, QuickPhone(skills = 1)))
        assertEquals(QuickState.SET_UP, QuickItems.state(QuickItem.TRANSCRIBE, QuickPhone(speechToGet = true)))
        assertEquals(QuickState.READY, QuickItems.state(QuickItem.TRANSCRIBE, QuickPhone(speech = true, speechToGet = true)))
        assertNull(QuickItems.state(QuickItem.TRANSCRIBE, QuickPhone()))
        assertEquals(QuickState.SET_UP, QuickItems.state(QuickItem.INTERPRETER, QuickPhone(translationToGet = true)))
        assertNull(QuickItems.state(QuickItem.INTERPRETER, QuickPhone(speech = true)))
        for (i in listOf(QuickItem.TALK, QuickItem.CAR)) assertEquals(QuickState.READY, QuickItems.state(i, QuickPhone()))
    }

    @Test fun theOrderIsKeptAndADamagedOneIsMended() {
        val mine = listOf(QuickItem.SKILLS, QuickItem.CAR)
        val b = QuickItems.shown(mine, QuickPhone(skills = 2, speechToGet = true))
        assertEquals(listOf(QuickItem.SKILLS, QuickItem.CAR, QuickItem.TALK), b.take(3).map { it.item })
        assertEquals(QuickButton(QuickItem.TRANSCRIBE, QuickState.SET_UP), b.first { it.item == QuickItem.TRANSCRIBE })
        assertEquals(QuickItem.DEFAULT, QuickOrder.of(listOf("nope", "talk", "talk")))
    }
}

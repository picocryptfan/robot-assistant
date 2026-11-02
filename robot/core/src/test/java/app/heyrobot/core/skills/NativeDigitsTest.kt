// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.StepPhrases
import app.heyrobot.core.lock.LockCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

/** F31: a machine value never takes the phone's digits (a Persian phone formats %d as ۰۷). */
class NativeDigitsTest {
    private val before = Locale.getDefault()

    @Before fun persian() = Locale.setDefault(Locale.forLanguageTag("fa-IR"))
    @After fun back() = Locale.setDefault(before)

    @Test fun theDefaultLocaleReallyHasNativeDigits() {
        assertEquals("۰۷", "%02d".format(7))
    }

    @Test fun lockTimes() = assertEquals("07:05", LockCodec.hm(LocalTime.of(7, 5)))

    @Test fun aTypedSkillDefault() = assertEquals("07:30", SkillEdit.typedDefault(ParamType.TIME, "7:30"))

    @Test fun aTaughtAlarm() {
        val s = SkillDraft.slots(listOf(SkillStep(StepKind.SET_ALARM, mapOf("hour" to 7, "minute" to 5))))
        assertEquals("07:05", s.single().value)
    }

    @Test fun aSpokenSkillTime() {
        val w = StepPhrases.Words("at 7:30")
        assertEquals("07:30", SkillValues.time(w, 0, w.size)?.first)
    }
}

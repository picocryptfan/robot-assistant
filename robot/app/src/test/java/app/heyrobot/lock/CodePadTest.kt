// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import app.heyrobot.core.lock.LockCodeRules
import app.heyrobot.core.lock.LockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.KeyStoreException

/**
 * The code pad's steps (CodePad) with the real vault on a folder, the Keystore in software and a stand-in for LockEngine and
 * state.json: no failure ends the pad; a Keystore error that may pass changes nothing and isn't a wrong try; a key gone for
 * good is noted by the engine before the code goes; with storage full a right code says it couldn't save, a wrong one
 * still brings the waits, and a new code that state.json can't show isn't kept.
 */
class CodePadTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    /**
     * LockEngine and state.json. [full]: every write throws, as editState does today when storage is full. [lost]: every
     * write returns as if done but isn't kept (a write that fails without saying so).
     */
    private inner class Engine : CodePad.Engine {
        var st = LockState()
        var full = false
        var lost = false
        var acted = 0
        var keyLosts = 0

        private fun write(next: LockState) {
            if (full) throw IOException("ENOSPC") // l10n: not text (a test's exception)
            if (!lost) st = next
        }

        override fun state() = st
        override fun tried(right: Boolean) = write(st.copy(code = if (right) LockCodeRules.RIGHT else LockCodeRules.wrong(st.code, now)))
        override fun codeSet(set: Boolean) = write(st.copy(codeSet = set, code = LockCodeRules.RIGHT))
        override fun keyLost() {
            write(st.copy(codeSet = false))
            keyLosts++
        }
        override fun act() {
            write(st.copy(edit = now + 5 * 60_000L))
            acted++
        }
    }

    private var now = 1_792_134_000_000L
    private val keys = SoftLockKeys()
    private val vault by lazy { LockCodeVault(File(tmp.root, LockCodeVault.FILE), keys) }
    private val engine = Engine()
    private val floor = CodePad.Floor()
    private val pad by lazy { CodePad(vault, engine, floor) }

    private fun withCode(code: String = "123456") {
        assertTrue(pad.set(code))
        assertTrue(engine.st.codeSet)
    }

    @Test fun aRightCodeDoesWhatItWasForAndWrongOnesBringTheWaits() {
        withCode()
        assertEquals(CodePad.Done.RIGHT, pad.check("123456", now))
        assertEquals(1, engine.acted)
        repeat(LockCodeRules.FREE_TRIES) { assertEquals(CodePad.Done.WRONG, pad.check("000000", now)) }
        assertEquals(LockCodeRules.FREE_TRIES, engine.st.code.fails)
        // The fifth wrong try brings a 30-second wait: nothing is checked, not even the right code.
        assertEquals(CodePad.Done.WAIT, pad.check("123456", now + 29_000))
        assertEquals(1, engine.acted)
        assertEquals(CodePad.Done.RIGHT, pad.check("123456", now + 30_000))
        assertEquals(LockCodeRules.RIGHT, engine.st.code)
        assertEquals(LockCodeRules.RIGHT, floor.tries)
    }

    @Test fun aKeystoreErrorThatMayPassChangesNothingAndIsntAWrongTry() {
        withCode()
        keys.deleted.clear()
        keys.failGet = { KeyStoreException("system error") }
        repeat(20) { assertEquals(CodePad.Done.NOT_NOW, pad.check("123456", now)) }
        repeat(20) { assertEquals(CodePad.Done.NOT_NOW, pad.check("000000", now)) }
        // The code, its key and the locks as they were; no try counted; the engine never told the key was lost.
        assertTrue(vault.isSet())
        assertEquals(emptyList<String>(), keys.deleted)
        assertTrue(engine.st.codeSet)
        assertEquals(0, engine.st.code.fails)
        assertEquals(0, floor.tries.fails)
        assertEquals(0, engine.keyLosts)
        assertEquals(0, engine.acted)
        keys.failGet = null
        assertEquals(CodePad.Done.RIGHT, pad.check("123456", now))
    }

    @Test fun aKeyGoneForGoodIsNotedByTheEngineBeforeTheCodeGoes() {
        withCode()
        repeat(LockCodeRules.FREE_TRIES) { assertEquals(CodePad.Done.WRONG, pad.check("000000", now)) }
        now += 30_000
        keys.keys.clear()
        // Storage full: the engine can't note it, so the code stays and the next try does it again.
        engine.full = true
        assertEquals(CodePad.Done.NOT_SAVED, pad.check("123456", now))
        assertTrue(vault.isSet())
        assertEquals(0, engine.keyLosts)
        engine.full = false
        assertEquals(CodePad.Done.LOST, pad.check("123456", now))
        assertEquals(1, engine.keyLosts)
        assertFalse(vault.isSet())
        assertFalse(engine.st.codeSet)
        assertEquals(CodePad.Done.NO_CODE, pad.check("123456", now))
        // A new code starts the count over: its first typo is a first wrong try, not a sixth.
        withCode("2468")
        assertEquals(CodePad.Done.WRONG, pad.check("0000", now))
        assertEquals(CodePad.Done.RIGHT, pad.check("2468", now))
    }

    @Test fun storageFullNeverEndsThePadAndWrongTriesStillWait() {
        withCode()
        engine.full = true
        // Right, but what it was for can't be saved: said, nothing done.
        assertEquals(CodePad.Done.NOT_SAVED, pad.check("123456", now))
        assertEquals(0, engine.acted)
        // Wrong tries aren't in state.json, but this process counts them: the waits come all the same.
        repeat(LockCodeRules.FREE_TRIES) { assertEquals(CodePad.Done.WRONG, pad.check("000000", now)) }
        assertEquals(0, engine.st.code.fails)
        assertEquals(LockCodeRules.FREE_TRIES, floor.tries.fails)
        assertEquals(CodePad.Done.WAIT, pad.check("000000", now + 1_000))
        // Another pad in this process (the cover's End early again) waits too.
        assertEquals(CodePad.Done.WAIT, CodePad(vault, engine, floor).check("000000", now + 1_000))
        assertEquals(CodePad.Done.WRONG, pad.check("000000", now + 30_000))
        assertEquals(CodePad.Done.WAIT, pad.check("000000", now + 60_000))
        assertEquals(LockCodeRules.FREE_TRIES + 1, pad.tries().fails)
        // Once storage is free again, the right code works and the count starts over.
        engine.full = false
        assertEquals(CodePad.Done.RIGHT, pad.check("123456", now + 30_000 + 60_000))
        assertEquals(LockCodeRules.RIGHT, floor.tries)
        assertEquals(1, engine.acted)
    }

    @Test fun aWriteThatSilentlyIsntKeptStillBringsTheWaits() {
        withCode()
        engine.lost = true
        repeat(LockCodeRules.FREE_TRIES) { assertEquals(CodePad.Done.WRONG, pad.check("000000", now)) }
        assertEquals(0, engine.st.code.fails)
        assertEquals(CodePad.Done.WAIT, pad.check("123456", now + 1_000))
    }

    @Test fun aNewCodeThatStateCantShowIsntKept() {
        // A first code: state.json can't take codeSet, so Settings would show no code and never ask for it.
        engine.full = true
        assertFalse(pad.set("123456"))
        assertFalse(vault.isSet())
        assertFalse(engine.st.codeSet)
        engine.full = false
        engine.lost = true
        assertFalse(pad.set("123456"))
        assertFalse(vault.isSet())
        engine.lost = false
        // A change while codeSet already says so: the new code is kept, though the tries' reset wasn't saved.
        withCode("123456")
        engine.full = true
        assertTrue(pad.set("2468"))
        assertEquals(CodePad.Done.WRONG, pad.check("123456", now))
        engine.full = false
        assertEquals(CodePad.Done.RIGHT, pad.check("2468", now))
        // The vault can't make one: the old code stays.
        keys.failMake = { KeyStoreException("system error") }
        assertFalse(pad.set("9999"))
        keys.failMake = null
        assertEquals(CodePad.Done.RIGHT, pad.check("2468", now))
    }

    @Test fun codeSetFollowsCodeJsonWhenThePadOpens() {
        withCode()
        // Removed while state.json couldn't take it: the code is gone, codeSet still says one is set.
        engine.full = true
        assertTrue(pad.remove())
        assertFalse(vault.isSet())
        assertTrue(engine.st.codeSet)
        pad.reconcile()
        assertTrue(engine.st.codeSet)
        engine.full = false
        pad.reconcile()
        assertFalse(engine.st.codeSet)
        // Set while state.json couldn't take it, and not taken back (a crash in between): codeSet catches up.
        assertTrue(vault.set("123456"))
        assertFalse(engine.st.codeSet)
        pad.reconcile()
        assertTrue(engine.st.codeSet)
        // Nothing to put right: nothing written.
        engine.st = engine.st.copy(code = LockCodeRules.wrong(LockCodeRules.RIGHT, now))
        pad.reconcile()
        assertEquals(1, engine.st.code.fails)
    }
}

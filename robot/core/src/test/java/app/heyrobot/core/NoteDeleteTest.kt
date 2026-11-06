// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

/** fix-note-delete: "delete the note about …" finds it, asks which when several fit, and Undo puts it back whole. */
class NoteDeleteTest {
    private val now = ZonedDateTime.of(2026, 11, 5, 9, 0, 0, 0, ZoneId.of("America/Halifax"))
    private fun route(t: String) = RoutineRouter.route(t, now, listOf("Evening routine"))
    private val gate = Note("n-gate", "Gate code", "4417 then pound", 1, 5)
    private val wifi = Note("n-wifi", "", "Wifi password at the cottage", 2, 6)
    private val shed = Note("n-shed", "Shed", "The gate code for the shed is 9902", 3, 7)
    private val shed2 = Note("n-shed2", "Shed gate code", "9903", 4, 8)

    @Test fun routesEnglish() {
        assertEquals(RoutineCommand.DeleteNote("gate code", false), route("delete the note about the gate code"))
        assertEquals(RoutineCommand.DeleteNote("wifi password", false), route("please remove my note on wifi password"))
        assertEquals(RoutineCommand.DeleteNote("last", false), route("delete my last note"))
        assertEquals(RoutineCommand.DeleteNote("last", false), route("delete the most recent note"))
    }

    @Test fun routesFrench() {
        assertEquals(RoutineCommand.DeleteNote("code de la barrière", true), route("supprime la note sur le code de la barrière"))
        assertEquals(RoutineCommand.DeleteNote("last", true), route("efface ma dernière note"))
        assertEquals(app.heyrobot.core.i18n.Lang.FR, RoutineRouter.lang("supprime la note sur le code", route("supprime la note sur le code")!!))
        assertEquals(app.heyrobot.core.i18n.ToolStrings.noteDeleteUnlock(), RoutineLockedPhone.refuseText(RoutineCommand.DeleteNote("x", true), locked = true))
    }

    @Test fun leftToTheModel() {
        for (t in listOf("delete my notes", "delete the note", "delete the note about the alarm code and set an alarm for 7", "delete my evening routine",
            "add a note about the gate code", "what's in my note about the gate code")) {
            assertFalse(t, route(t) is RoutineCommand.DeleteNote)
        }
    }

    @Test fun oneMatch() = assertEquals(NoteDelete.Plan.One(wifi), NoteDelete.plan("wifi password", listOf(gate, wifi, shed)))

    @Test fun twoMatchesAskWhich() {
        val p = NoteDelete.plan("gate code", listOf(gate, wifi, shed2))
        assertTrue(p is NoteDelete.Plan.Several)
        assertEquals(setOf(gate, shed2), (p as NoteDelete.Plan.Several).notes.toSet())
    }

    @Test fun noMatch() {
        assertEquals(NoteDelete.Plan.None, NoteDelete.plan("dentist", listOf(gate, wifi)))
        assertEquals(NoteDelete.Plan.None, NoteDelete.plan("last", emptyList()))
        assertEquals(NoteDelete.Plan.One(shed2), NoteDelete.plan("last", listOf(gate, wifi, shed2)))
    }

    @Test fun undoPutsItBackWhole() {
        val dir = Files.createTempDirectory("notes").toFile()
        try {
            val store = NoteStore(dir)
            assertTrue(store.save(gate) && store.save(wifi))
            val undos = NoteDelete.Undos()
            val key = undos.delete(store, gate)!!
            assertTrue(NoteDelete.isUndo(key))
            assertFalse(NoteDelete.isUndo(gate.id))
            assertNull(store.get(gate.id))
            // lists-undo-fix: a restore that couldn't be written keeps its Undo, to try again.
            dir.setWritable(false)
            assertFalse(undos.restore(store, key))
            assertTrue(undos.has(key))
            dir.setWritable(true)
            assertTrue(undos.restore(store, key))
            assertFalse(undos.has(key))
            assertEquals(gate, store.get(gate.id))
            assertFalse(undos.restore(store, key))
            assertNull(undos.delete(store, Note("n-none", "", "x", 1, 1)))
        } finally {
            dir.deleteRecursively()
        }
    }

    // review-1.2.4 finding 1: the waiting card's title, said aloud before "Say yes or no.", names the note.
    @Test fun theAskNamesTheNote() {
        val ask = app.heyrobot.core.i18n.ToolStrings.noteDeleteAsk("title" to "gate code")
        assertEquals("Delete the note “gate code”?", ask.render(app.heyrobot.core.i18n.Langs.of(app.heyrobot.core.i18n.Lang.EN)))
        assertEquals("Supprimer la note «\u00A0gate code\u00A0»\u202f?", ask.render(app.heyrobot.core.i18n.Langs.of(app.heyrobot.core.i18n.Lang.FR)))
    }
}

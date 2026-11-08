// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.connect.ConnFixtures
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.SkillStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Skills 1.3 plan S3.4: the keys you trust, who signed a file, and a file's skills made ready to import (design §11.2). */
class SkillShareTest {
    private fun signer(): SkillSigner {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return object : SkillSigner {
            override val publicKey: ByteArray = pair.public.encoded
            override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
        }
    }
    private val other = signer()
    private val otherFp = SkillSig.fingerprint(other.publicKey)
    private val mineFp = "ab".repeat(32)
    private val now = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZoneOffset.UTC)
    private var n = 0
    private fun ids() = "s-imp00" + (n++)
    private val ha = ConnFixtures.homeAssistant("c-myha01", ConnFixtures.dim.copy(id = "light_dim"), ConnFixtures.garage.copy(id = "lock"))
    private val haDimOnly = ConnFixtures.homeAssistant("c-myha02", ConnFixtures.dim.copy(id = "light_dim"))

    private fun file(skills: List<Skill>, s: SkillSigner? = other): ExportRead.Ok {
        val w = SkillExport.write(skills, "Robot 1.3.0", 1793815440123L, mapOf(SkillFixtures.HA to ConnKind.HOME_ASSISTANT)::get, s) as ExportWrite.Ok
        return SkillExport.read(w.bytes, ::ids, 5_000L) as ExportRead.Ok
    }

    @Test fun aFingerprintIsAll64DigitsInAnyForm() {
        assertEquals(otherFp, SkillShare.fingerprintOf(SkillShare.grouped(otherFp)))
        assertEquals(otherFp, SkillShare.fingerprintOf(otherFp.uppercase().chunked(2).joinToString(":")))
        assertNull(SkillShare.fingerprintOf(otherFp.drop(1)))
        assertNull(SkillShare.fingerprintOf(SkillSig.shown(otherFp)))
        assertNull(SkillShare.fingerprintOf(otherFp.dropLast(1) + "g"))
        assertEquals(16, SkillShare.grouped(otherFp).split(' ').size)
    }

    @Test fun trustedKeysReadBackAndABadEntryIsLeftOut() {
        val keys = SkillShare.add(emptyList(), SkillShare.grouped(otherFp), " Tablet ", mineFp, 7L)
        assertEquals(listOf(TrustedKey(otherFp, "Tablet", 7L)), keys)
        assertEquals(keys, SkillShare.readTrusted(SkillShare.writeTrusted(keys)))
        val bad = """{"v":1,"keys":[{"fingerprint":"$otherFp","name":"A","added":1},{"fingerprint":"$otherFp","name":"B"},""" +
            """{"fingerprint":"abc","name":"C"},{"fingerprint":"$mineFp","name":""}]}"""
        assertEquals(listOf(TrustedKey(otherFp, "A", 1L)), SkillShare.readTrusted(bad))
        assertEquals(emptyList<TrustedKey>(), SkillShare.readTrusted("""{"v":2,"keys":[{"fingerprint":"$otherFp","name":"A"}]}"""))
        assertEquals(emptyList<TrustedKey>(), SkillShare.readTrusted("not json"))
        // Why one can't be added: not a fingerprint, your own key, already there, no name, full.
        assertEquals(SkillStrings.keyNotFingerprint(), SkillShare.addProblem(keys, "12 34", "X", mineFp))
        assertEquals(SkillStrings.keyIsYours(), SkillShare.addProblem(keys, mineFp, "X", mineFp))
        assertEquals(SkillStrings.keyAlready("name" to "Tablet"), SkillShare.addProblem(keys, otherFp, "X", mineFp))
        assertEquals(SkillStrings.keyName(), SkillShare.addProblem(keys, "cd".repeat(32), " ", mineFp))
        val full = (0 until SkillShare.MAX_TRUSTED).map { TrustedKey(it.toString(16).padStart(64, '0'), "K$it", 0L) }
        assertEquals(SkillStrings.keyFull("n" to SkillShare.MAX_TRUSTED), SkillShare.addProblem(full, "cd".repeat(32), "X", mineFp))
        assertEquals(full, SkillShare.add(full, "cd".repeat(32), "X", mineFp, 0L))
    }

    @Test fun aTrustedKeyNamesTheSignerAndChangesNothingElse() {
        val r = file(listOf(SkillFixtures.headingHome()))
        val trusted = listOf(TrustedKey(otherFp, "Tablet", 1L))
        assertEquals(SignedBy.Trusted("Tablet"), SkillShare.signedBy(r.signer, mineFp, trusted))
        assertEquals("From a file · signed by your key “Tablet”", SkillShare.provenance(SkillShare.signedBy(r.signer, mineFp, trusted)).en())
        val unknown = SkillShare.signedBy(r.signer, mineFp, emptyList())
        assertEquals("From a file · signed by ${SkillSig.shown(otherFp)} … (a key you don't trust)", SkillShare.provenance(unknown).en())
        assertEquals(SignedBy.ThisPhone, SkillShare.signedBy(r.signer, otherFp, trusted))
        assertEquals("From a file · not signed", SkillShare.provenance(SkillShare.signedBy(file(listOf(SkillFixtures.headingHome()), null).signer, mineFp, trusted)).en())
        // The skill starts off and from content (Just do it only after you edit it), whoever signed it.
        for (t in listOf(trusted, emptyList())) {
            val k = SkillShare.prepare(r, NameContext(), now, Langs.EN).single().skill
            SkillShare.signedBy(r.signer, mineFp, t)
            assertEquals(SkillLevel.OFF, k.level)
            assertTrue(k.provenance.fromContent)
            assertEquals(otherFp, k.provenance.imported!!.signer)
            assertTrue(SkillCheck.problems(k.copy(level = SkillLevel.JUST_DO_IT)).contains(SkillStrings.contentCheck()))
        }
        assertEquals(2, SkillShare.logSigned(SignedBy.Trusted("Tablet")))
        assertEquals(1, SkillShare.logSigned(unknown))
        assertEquals(0, SkillShare.logSigned(SignedBy.Nobody))
    }

    @Test fun aNameThatClashesIsRenamedAndItsOtherNamesLeftEmpty() {
        val r = file(listOf(SkillFixtures.headingHome(), SkillFixtures.goodNight()))
        val mine = NameContext(skills = listOf(SkillFixtures.goodNight("s-mine01")))
        val items = SkillShare.prepare(r, mine, now, Langs.EN)
        assertEquals(listOf("Heading home", "Good night (imported)"), items.map { it.skill.name })
        assertEquals(listOf(false, true), items.map { it.renamed })
        assertEquals(SkillFixtures.headingHome().namesEn, items[0].skill.namesEn)
        assertTrue(items[1].skill.namesEn.isEmpty() && items[1].skill.namesFr.isEmpty() && items[1].skill.namesMore.isEmpty())
        // Already one "(imported)": the next is "(imported 2)"; in French, « (importée) ».
        val twice = SkillShare.prepare(r, mine.copy(skills = mine.skills + items[1].skill.copy(id = "s-mine02")), now, Langs.EN)
        assertEquals("Good night (imported 2)", twice[1].skill.name)
        assertEquals("Good night (importée)", SkillShare.prepare(r, mine, now, Langs(Lang.FR, Lang.FR, Lang.FR))[1].skill.name)
        // A long name is cut to fit.
        val long = SkillFixtures.simple("s-long01", "A".repeat(SkillCheck.MAX_NAME))
        val l = SkillShare.prepare(file(listOf(long)), NameContext(skills = listOf(long)), now, Langs.EN).single().skill.name
        assertTrue(l, l.length <= SkillCheck.MAX_NAME && l.endsWith(" (imported)"))
    }

    @Test fun eachRefIsBoundToAConnectionOfItsKindOrTheSkillNeedsAttention() {
        val r = file(listOf(SkillFixtures.goodNight()))
        val item = SkillShare.prepare(r, NameContext(), now, Langs.EN).single()
        val need = item.needs.single()
        assertEquals("c-ref001", need.ref)
        // Only a Home Assistant connection with both operations fits.
        assertEquals(listOf(ha), SkillShare.candidates(need, listOf(ha, haDimOnly, ConnFixtures.ntfy(), ConnFixtures.handOff())))
        val bound = SkillShare.bind(item.skill, mapOf(need.ref to ha.id))
        assertTrue(bound.steps.filter { it.conn != null }.all { it.conn == ha.id })
        assertEquals(item.skill.steps.map { it.conn == null }, bound.steps.map { it.conn == null })
        assertEquals(emptyList<ExportNeed>(), SkillShare.missing(item, mapOf(need.ref to ha.id)))
        // Not picked: saved as it is, off, and its connection steps need attention.
        assertEquals(listOf(need), SkillShare.missing(item, emptyMap()))
        val links = app.heyrobot.core.connect.ConnLinks(listOf(ha), app.heyrobot.core.termux.ScriptLinks(emptyList()))
        assertFalse(SkillCheck.attention(item.skill, links).isEmpty())
        assertTrue(SkillCheck.valid(item.skill))
        assertNotNull(SkillShare.bind(item.skill, emptyMap()).steps.firstOrNull { it.conn == "c-ref001" })
    }
}

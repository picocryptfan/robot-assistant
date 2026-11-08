// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.connect.ConnKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Skills 1.3 plan S3.3: a `.robotskill` file written, signed, read and refused (design §11.1, §11.2). */
class SkillExportTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val signer = object : SkillSigner {
        override val publicKey: ByteArray = pair.public.encoded
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
    }
    private val kinds = mapOf(SkillFixtures.HA to ConnKind.HOME_ASSISTANT)
    private var n = 0
    private fun ids(): String = "s-imp00" + (n++)

    private fun write(skills: List<Skill>, s: SkillSigner? = signer): ByteArray =
        (SkillExport.write(skills, "Robot 1.3.0", 1793815440123L, kinds::get, s) as ExportWrite.Ok).bytes

    private fun read(b: ByteArray): ExportRead = SkillExport.read(b, ::ids, 5_000L)
    private fun refusal(b: ByteArray): ExportRefusal? = (read(b) as? ExportRead.Refused)?.why
    private fun sha(b: ByteArray) = SkillSig.hex(MessageDigest.getInstance("SHA-256").digest(b))

    @Test fun aSignedFileReadsBackOffAndFromItsSigner() {
        val mine = listOf(SkillFixtures.headingHome(), SkillFixtures.goodNight())
        val bytes = write(mine)
        val r = read(bytes) as ExportRead.Ok
        assertEquals(SkillSig.fingerprint(signer.publicKey), r.signer)
        assertEquals(sha(bytes), r.fileSha256)
        assertEquals(0, r.refused)
        assertEquals("2026-11-04T18:04:00Z", r.exported)
        assertEquals(listOf(ExportNeed("c-ref001", ConnKind.HOME_ASSISTANT, listOf("light_dim", "lock"))), r.needs)
        assertEquals(listOf("s-imp000", "s-imp001"), r.skills.map { it.id })
        for ((got, was) in r.skills.zip(mine)) {
            assertEquals(SkillLevel.OFF, got.level)
            assertEquals(SkillProvenance(SkillMade.IMPORT, true, 5_000L, 5_000L, SkillImport(sha(bytes), r.signer)), got.provenance)
            assertEquals(was.steps.map { if (it.conn != null) it.copy(conn = "c-ref001") else it }, got.steps)
            assertTrue(got.copy(steps = was.steps).sameContent(was))
        }
        val unsigned = read(write(mine, null)) as ExportRead.Ok
        assertNull(unsigned.signer)
        assertNull(unsigned.skills[0].provenance.imported!!.signer)
    }

    /** fix-skills13-security 3: a file that names one of your connections' ids, or any ref not "c-ref" and three digits, binds to nothing. */
    @Test fun aRefNotOfTheRefShapeBindsOnlyToWhatYouPick() {
        val mine = listOf(SkillFixtures.headingHome(), SkillFixtures.goodNight())
        val text = String(write(mine, null), Charsets.UTF_8)
        for (bad in listOf(SkillFixtures.HA, "c-refabc", "c-zzz001")) {
            val r = read(text.replace("c-ref001", bad).toByteArray()) as ExportRead.Ok
            assertEquals(bad, 0, r.refused)
            assertEquals(bad, listOf(ExportNeed("c-ref001", ConnKind.HOME_ASSISTANT, listOf("light_dim", "lock"))), r.needs)
            for ((got, was) in r.skills.zip(mine)) assertEquals(bad, was.steps.map { if (it.conn != null) it.copy(conn = "c-ref001") else it }, got.steps)
            assertTrue(r.skills.all { s -> s.steps.all { it.conn == null || SkillExport.isRef(it.conn!!) } })
        }
        assertTrue(SkillExport.isRef("c-ref999"))
        assertFalse(SkillExport.isRef("c-ref1000") || SkillExport.isRef("c-refabc") || SkillExport.isRef("c-ref00"))
    }

    @Test fun nothingPrivateLeavesThePhone() {
        val text = String(write(listOf(SkillFixtures.headingHome(), SkillFixtures.goodNight())), Charsets.UTF_8)
        for (never in listOf("s-k3f9q2", "s-night1", SkillFixtures.HA, "provenance", "\"level\":\"", "\"rev\"", "1760800000000", "ask_first", "\"id\":\"s-")) {
            assertFalse(never, never in text)
        }
        val o = JSONObject(text)
        assertEquals(setOf("format", "v", "made_with", "exported", "skills", "needs", "sig"), o.keys().asSequence().toSet())
        for (i in 0 until o.getJSONArray("skills").length()) assertTrue(SkillExport.SHARED.containsAll(o.getJSONArray("skills").getJSONObject(i).keys().asSequence().toSet()))
        assertEquals(Jcs.text(o), text)
    }

    @Test fun oneChangedByteIsRefusedAsChangedAfterItWasSigned() {
        val bytes = write(listOf(SkillFixtures.headingHome(), SkillFixtures.goodNight()))
        val text = String(bytes, Charsets.UTF_8)
        assertEquals(ExportRefusal.CHANGED, refusal(text.replace("Heading home", "Heading homf").toByteArray()))
        assertEquals(ExportRefusal.CHANGED, refusal(text.replace("\"v\":1", "\"v\": 1").toByteArray()))
        assertEquals(ExportRefusal.CHANGED, refusal(text.replace("\"v\":1", "\"v\":1.0").toByteArray()))
        // Every byte: refused, and as changed, except where the change unmakes the signature's own key (then it isn't signed).
        val sigAt = text.indexOf("\"sig\":{")
        for (i in bytes.indices) {
            val b = bytes.copyOf().also { it[i] = if (it[i] == 'x'.code.toByte()) 'y'.code.toByte() else 'x'.code.toByte() }
            val why = refusal(b) ?: throw AssertionError("byte $i read")
            if (why != ExportRefusal.CHANGED) assertTrue("byte $i: $why", i in sigAt until sigAt + 7 && why == ExportRefusal.NOT_SKILLS)
        }
        // The one exception the reader makes: a higher "v" reads as a newer Robot's file.
        assertEquals(ExportRefusal.TOO_NEW, refusal(text.replace("\"v\":1", "\"v\":2").toByteArray()))
    }

    @Test fun aSecondFormOfTheSameSignatureIsRefused() {
        val o = JSONObject(String(write(listOf(SkillFixtures.headingHome())), Charsets.UTF_8))
        val sig = Base64.getDecoder().decode(o.getJSONObject("sig").getString("value"))
        val s = BigInteger(1, sig.copyOfRange(32, 64))
        val high = SkillSig.hex(sig.copyOfRange(0, 32)) + N.subtract(s).toString(16).padStart(64, '0')
        o.getJSONObject("sig").put("value", Base64.getEncoder().encodeToString(bytes(high)))
        assertEquals(ExportRefusal.CHANGED, refusal(Jcs.bytes(o)))
    }

    @Test fun fingerprintsMatchAFixedVector() {
        // RFC 6979 A.2.5's P-256 key; its fingerprint and the SHA-256 of "sample" signature computed with OpenSSL 3.6.
        val spki = Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYP7UuiVanTHJYet0xjVtaMBJuJI7Yfps5mliLmDyn7Z5A/4QCLi8maQa6elWKLxk8vGyDC1+n1F3o8KU1EYimQ==")
        assertEquals("5a7a78cca4a0f420d9bc62bb669c3c2759e39f723d3ae10dcbe0f0815a07ecd4", SkillSig.fingerprint(spki))
        assertEquals("5A7A 78CC A4A0 F420", SkillSig.shown(SkillSig.fingerprint(spki)))
        val r = "efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716"
        val s = "f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8"
        val der = bytes("3046022100" + r + "022100" + s)
        val raw = SkillSig.raw(der)!!
        assertTrue(SkillSig.verify(spki, "sample".toByteArray(), raw))
        assertFalse(SkillSig.verify(spki, "sample".toByteArray(), bytes(r + s))) // the high-s form: one signature, one form
        assertFalse(SkillSig.verify(spki, "samplf".toByteArray(), raw))
        assertNull(SkillSig.publicKey(spki.copyOf().also { it[90] = (it[90] + 1).toByte() })) // off the curve
        assertEquals(SkillSig.fingerprint(signer.publicKey), (SkillExport.write(listOf(SkillFixtures.headingHome()), "R", 0L, kinds::get, signer) as ExportWrite.Ok).signer)
    }

    @Test fun eachRefusalSaysWhy() {
        assertEquals(ExportRefusal.TOO_LARGE, refusal(ByteArray(SkillExport.MAX_BYTES + 1) { ' '.code.toByte() }))
        assertEquals(ExportRefusal.NOT_SKILLS, refusal("{}".toByteArray()))
        assertEquals(ExportRefusal.NOT_SKILLS, refusal(byteArrayOf(0xc3.toByte(), 0x28)))
        val o = JSONObject(String(write(listOf(SkillFixtures.headingHome()), null), Charsets.UTF_8))
        assertEquals(ExportRefusal.NOT_SKILLS, refusal(Jcs.bytes(JSONObject(o.toString()).put("format", "robot.backup"))))
        assertEquals(ExportRefusal.NOT_SKILLS, refusal(Jcs.bytes(JSONObject(o.toString()).put("extra", 1))))
        assertEquals(ExportRefusal.TOO_NEW, refusal(Jcs.bytes(JSONObject(o.toString()).put("v", 2))))
        val keyed = JSONObject(o.toString())
        keyed.getJSONArray("skills").getJSONObject(0).getJSONArray("steps").getJSONObject(0).getJSONObject("args").put("message", "use ghp_Ab12Cd34Ef56Gh78Ij90Kl")
        assertEquals(ExportRefusal.KEY, refusal(Jcs.bytes(keyed)))
        // A skill trying to set its own level or id, or naming a connection the file doesn't list, is refused whole.
        for (bad in listOf<(JSONObject) -> Unit>({ it.put("level", "just_do_it") }, { it.put("id", "s-aaaaaa") },
            { it.getJSONArray("steps").put(JSONObject().put("use", "conn:c-ref009:lock").put("args", JSONObject())) })) {
            val f = JSONObject(o.toString())
            bad(f.getJSONArray("skills").getJSONObject(0))
            assertEquals(ExportRefusal.NONE, refusal(Jcs.bytes(f)))
        }
        for (m in ExportRefusal.entries.map { it.msg } + ExportProblem.entries.map { it.msg } + ExportFlagKind.entries.map { it.msg }) assertNotNull(m.id, m.fr)
    }

    @Test fun exportRefusesAKeyOrAMissingConnection() {
        val keyed = SkillFixtures.headingHome().let { h -> h.copy(steps = h.steps + SkillStep.of(StepKind.SAY, mapOf("text" to "key sk-Ab12Cd34Ef56Gh78Ij90"))) }
        assertEquals(ExportWrite.Refused(ExportProblem.KEY, "Heading home"), SkillExport.write(listOf(keyed), "R", 0L, kinds::get, null))
        assertEquals(ExportWrite.Refused(ExportProblem.CONNECTION, "Good night"), SkillExport.write(listOf(SkillFixtures.goodNight()), "R", 0L, { null }, null))
        assertFalse(SkillExport.keyShaped("sensor.living_room_temperature_01"))
        assertFalse(SkillExport.keyShaped("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(SkillExport.keyShaped("eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiIxMjM0In0.abc"))
    }

    @Test fun thePreviewFlagsWhatLooksPersonal() {
        val h = SkillFixtures.headingHome()
        val more = h.copy(steps = h.steps + listOf("Write to sam@example.com", "Meet at 12 Main Street", "Call 902-555-0123", "Code 4417 for the door")
            .map { SkillStep.of(StepKind.SAY, mapOf("text" to it)) })
        assertEquals(listOf("Sam" to ExportFlagKind.PERSON, "On my way home." to ExportFlagKind.MESSAGE, "Write to sam@example.com" to ExportFlagKind.EMAIL,
            "Meet at 12 Main Street" to ExportFlagKind.ADDRESS, "Call 902-555-0123" to ExportFlagKind.NUMBER, "Code 4417 for the door" to ExportFlagKind.PRIVATE),
            SkillExport.preview(listOf(more)).map { it.value to it.kind })
        assertEquals(emptyList<ExportFlag>(), SkillExport.preview(listOf(SkillFixtures.goodNight())))
    }

    @Test fun canonicalFormIsRfc8785s() {
        // RFC 8785 Appendix B's numbers, and §3.2.3's key order.
        val numbers = mapOf(0.0 to "0", -0.0 to "0", 5e-324 to "5e-324", 1.7976931348623157e308 to "1.7976931348623157e+308",
            9007199254740992.0 to "9007199254740992", 1e21 to "1e+21", 999999999999999900000.0 to "999999999999999900000",
            0.000001 to "0.000001", 9.999999999999997e-7 to "9.999999999999997e-7", 333333333.3333333 to "333333333.3333333",
            4.5 to "4.5", 0.002 to "0.002", 1e-7 to "1e-7", -1.5 to "-1.5", 20.0 to "20", 1e30 to "1e+30")
        for ((d, s) in numbers) assertEquals(s, Jcs.number(d))
        val o = JSONObject().put("\u20ac", 1).put("\r", 2).put("\ufb33", 3).put("1", 4).put("\ud83d\ude00", 5).put("\u0080", 6).put("\u00f6", 7)
        assertEquals("{\"\\r\":2,\"1\":4,\"\u0080\":6,\"\u00f6\":7,\"\u20ac\":1,\"\ud83d\ude00\":5,\"\ufb33\":3}", Jcs.text(o))
        assertEquals("[\"a\\u000fb\\\"\\\\\",true,null,[]]", Jcs.text(JSONArray().put("a\u000fb\"\\").put(true).put(JSONObject.NULL).put(JSONArray())))
        for (bad in listOf<Any>("\ud800", Double.NaN, 9007199254740993L)) try {
            Jcs.text(bad)
            fail("$bad")
        } catch (_: IllegalArgumentException) {}
    }

    private val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

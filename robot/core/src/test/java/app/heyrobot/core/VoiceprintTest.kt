package app.heyrobot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * The "me" voiceprint's file (docs/conversation-mode.md 3.3, 8; phase 5.3). The phone encrypts with an Android Keystore
 * key; here a stand-in sealer checks the format, and the JVM's own AES-GCM shows a changed byte or another key opens nothing.
 */
class VoiceprintTest {
    private val model = VoiceBundle.SPEAKERS.id

    private fun unit(seed: Int, dim: Int = 192): FloatArray {
        val r = java.util.Random(seed.toLong())
        return SpeakerClusterer.unit(FloatArray(dim) { r.nextGaussian().toFloat() })
    }

    /** Not encryption: reverses the bytes and keeps a fixed IV, so the format can be checked byte by byte. */
    private object Mirror : VoiceprintCodec.Sealer {
        val iv = ByteArray(12) { it.toByte() }
        override fun seal(plain: ByteArray, aad: ByteArray) = iv.copyOf() to (aad + plain.reversedArray())
        override fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
            require(iv.contentEquals(this.iv) && sealed.copyOfRange(0, aad.size).contentEquals(aad))
            return sealed.copyOfRange(aad.size, sealed.size).reversedArray()
        }
    }

    /** The JVM's AES-GCM with a key in memory, as the Keystore's would be used on the phone (fresh IV each time). */
    private class Gcm(val key: javax.crypto.SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : VoiceprintCodec.Sealer {
        override fun seal(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            c.updateAAD(aad)
            return c.iv to c.doFinal(plain)
        }

        override fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return c.doFinal(sealed)
        }
    }

    @Test fun thePlainVoiceprintIsSmallAndExact() {
        val v = Voiceprint(model, unit(1), 3)
        val b = VoiceprintCodec.encode(v)
        // "RVP" 1, the model id, clips and dimension, then 192 floats: 768 bytes of numbers.
        assertEquals(4 + 1 + model.length + 2 + 2 + 768, b.size)
        assertEquals("RVP", String(b, 0, 3, Charsets.US_ASCII))
        assertEquals(v, VoiceprintCodec.decode(b))
        // Big-endian floats after the header.
        val first = ByteBuffer.wrap(b, 4 + 1 + model.length + 4, 4).float
        assertEquals(v.vector[0], first, 0f)
        // Never the numbers in its text form.
        assertFalse(v.toString().contains(v.vector[0].toString()))
    }

    @Test fun anythingElseIsNotAVoiceprint() {
        val good = VoiceprintCodec.encode(Voiceprint(model, unit(2), 3))
        assertNull(VoiceprintCodec.decode(good.copyOf(good.size - 1)))
        assertNull(VoiceprintCodec.decode(good + byteArrayOf(0)))
        assertNull(VoiceprintCodec.decode(ByteArray(0)))
        assertNull(VoiceprintCodec.decode("RVP2xxxxxxx".toByteArray()))
        // Not unit length (a damaged number), or not finite.
        val bad = good.copyOf().also { ByteBuffer.wrap(it).putFloat(4 + 1 + model.length + 4, 3f) }
        assertNull(VoiceprintCodec.decode(bad))
        val nan = good.copyOf().also { ByteBuffer.wrap(it).putFloat(4 + 1 + model.length + 4, Float.NaN) }
        assertNull(VoiceprintCodec.decode(nan))
        // Too few or too many numbers to be an embedding.
        try {
            VoiceprintCodec.encode(Voiceprint(model, SpeakerClusterer.unit(FloatArray(8) { 1f }), 3))
            assertTrue(false)
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun theSealedFileWrapsIt() {
        val v = Voiceprint(model, unit(3), 3)
        val f = VoiceprintCodec.seal(v, Mirror)
        // "RVE" 1, the IV's length, the IV, then what the sealer gave.
        assertEquals("RVE", String(f, 0, 3, Charsets.US_ASCII))
        assertEquals(1, f[3].toInt())
        assertEquals(12, f[4].toInt())
        assertArrayEquals(Mirror.iv, f.copyOfRange(5, 17))
        assertEquals(v, VoiceprintCodec.open(f, Mirror))
        // The plain voiceprint is not in the file as such.
        val plain = VoiceprintCodec.encode(v)
        assertFalse(String(f, Charsets.ISO_8859_1).contains(String(plain.copyOfRange(10, 40), Charsets.ISO_8859_1)))
        // Not this format, cut short, or a sealer that refuses: nothing.
        assertNull(VoiceprintCodec.open(VoiceprintCodec.encode(v), Mirror))
        assertNull(VoiceprintCodec.open(f.copyOf(10), Mirror))
        assertNull(VoiceprintCodec.open(f.copyOf().also { it[4] = 0 }, Mirror))
        assertNull(VoiceprintCodec.open(f, object : VoiceprintCodec.Sealer {
            override fun seal(plain: ByteArray, aad: ByteArray) = throw IllegalStateException()
            override fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray) = throw javax.crypto.AEADBadTagException()
        }))
    }

    @Test fun withAesGcmAChangedByteOrAnotherKeyOpensNothing() {
        val v = Voiceprint(model, unit(4), 3)
        val k = Gcm()
        val f = VoiceprintCodec.seal(v, k)
        assertEquals(v, VoiceprintCodec.open(f, k))
        // A fresh IV each time: two files of the same voiceprint differ.
        assertFalse(f.contentEquals(VoiceprintCodec.seal(v, k)))
        for (i in listOf(3, 6, f.size / 2, f.size - 1)) assertNull("byte $i", VoiceprintCodec.open(f.copyOf().also { it[i] = (it[i] + 1).toByte() }, k))
        // The Keystore key is gone (a factory reset of the key, a restore elsewhere): unreadable, never a wrong voice.
        assertNull(VoiceprintCodec.open(f, Gcm()))
    }

    @Test fun threeRecordingsOfOneVoiceMakeAVoiceprint() {
        val me = unit(5)
        // Three readings near one voice (cosine about 0.8 to it).
        val clips = (1..3).map { k -> SpeakerClusterer.unit(FloatArray(192) { me[it] * 2f + unit(10 + k)[it] * 1.2f }) }
        val r = Enrolment.make(model, clips)
        assertTrue(r.toString(), r is Enrolment.Result.Ok)
        val v = (r as Enrolment.Result.Ok).voiceprint
        assertEquals(3, v.clips)
        assertEquals(model, v.model)
        assertTrue(SpeakerClusterer.dot(v.vector, me) > 0.9f)
        assertEquals(v, VoiceprintCodec.decode(VoiceprintCodec.encode(v)))
        // Someone else read one of them: not one voice.
        assertEquals(Enrolment.Result.NotAlike, Enrolment.make(model, clips.take(2) + unit(99)))
        // Too few, or a recording too short to embed.
        assertEquals(Enrolment.Result.TooFew, Enrolment.make(model, clips.take(2)))
        assertEquals(Enrolment.Result.TooFew, Enrolment.make(model, clips.take(2) + FloatArray(192)))
        assertEquals(Enrolment.Result.TooFew, Enrolment.make(model, clips.take(2) + unit(6, 128)))
        // The sentences are in your language, and have no numbers (read the same by everyone).
        assertEquals(Enrolment.SENTENCES_FR, Enrolment.sentences("fr"))
        assertEquals(Enrolment.SENTENCES_EN, Enrolment.sentences("en"))
        for (s in Enrolment.SENTENCES_EN + Enrolment.SENTENCES_FR) assertFalse(s, s.any { it.isDigit() })
        assertEquals(Enrolment.CLIPS, Enrolment.SENTENCES_EN.size)
    }
}

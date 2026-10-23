// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileKeyException
import app.heyrobot.core.files.FileKeyProblem
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileStore
import app.heyrobot.core.files.IndexLoad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException

/**
 * robot_files_v1 (files-1.4-plan.md §2.3, F1's FileKeys contract), with Android's own classes (Robolectric) and the
 * Keystore in software ([SoftFileKeystore]; Robolectric has no AndroidKeyStore, so what only a phone can show is in the
 * Test Bench): how the key is made, never over an existing alias, the exception mapping, and core's store on top of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreFileKeysTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val aad = "robot-files-v1|3|0123456789abcdef0123456789abcdef".toByteArray()
    private val key = ByteArray(32) { (it * 7).toByte() }

    private fun problem(block: () -> Unit): FileKeyProblem? = try {
        block()
        null
    } catch (x: FileKeyException) {
        x.problem
    }

    @Test fun theKeyIsMadeAsThePlanSays() {
        val s = KeystoreFileKeys.spec()
        assertEquals("robot_files_v1", s.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, s.purposes)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), s.blockModes.toList())
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_NONE), s.encryptionPaddings.toList())
        assertEquals(256, s.keySize)
        assertTrue(s.isUnlockedDeviceRequired)
        assertTrue(s.isRandomizedEncryptionRequired)
        // TEE: not StrongBox (slow for this), and no user authentication on top of the unlock (as memory's key).
        assertFalse(s.isStrongBoxBacked)
        assertFalse(s.isUserAuthenticationRequired)
        assertFalse(s.isUserPresenceRequired)
        assertFalse(s.isUserConfirmationRequired)
        assertEquals(0, s.signaturePaddings.size)
    }

    @Test fun madeOnlyWhenAskedAndThereIsNoAlias() {
        val ks = SoftFileKeystore(present = false)
        val k = KeystoreFileKeys(ks)
        // Not asked to make one: gone.
        assertEquals(FileKeyProblem.GONE, problem { k.wrap(key, aad, create = false) })
        assertEquals(0, ks.generated)
        // Asked: made once, with the plan's spec, and then used.
        val w = k.wrap(key, aad, create = true)
        assertEquals(1, ks.generated)
        assertTrue(ks.specs.single().isUnlockedDeviceRequired)
        assertArrayEquals(key, k.unwrap(w, aad))
        // Asked again while it exists: the same key, never a new one over it.
        val first = ks.keys.getValue(KeystoreFileKeys.ALIAS)
        k.wrap(key, aad, create = true)
        assertEquals(1, ks.generated)
        assertTrue(first === ks.keys.getValue(KeystoreFileKeys.ALIAS))
    }

    @Test fun anAliasThatCantBeReadIsNeverReplaced() {
        // The Keystore names the alias but gives no key: gone, and nothing made over it, even when asked to make one.
        val ks = SoftFileKeystore(present = false).apply { hollow = true }
        assertEquals(FileKeyProblem.GONE, problem { KeystoreFileKeys(ks).wrap(key, aad, create = true) })
        assertEquals(0, ks.generated)
        // Android invalidated it: gone, and still nothing made.
        val inv = SoftFileKeystore().apply { failKey = { UnrecoverableKeyException("x").apply { initCause(KeyPermanentlyInvalidatedException()) } } }
        assertEquals(FileKeyProblem.GONE, problem { KeystoreFileKeys(inv).wrap(key, aad, create = true) })
        assertEquals(0, inv.generated)
        // The Keystore busy or the phone locked: not now, and still nothing made.
        val busy = SoftFileKeystore().apply { locked = true }
        assertEquals(FileKeyProblem.LOCKED, problem { KeystoreFileKeys(busy).wrap(key, aad, create = true) })
        assertEquals(0, busy.generated)
    }

    @Test fun theExceptionsMapToLockedGoneOrBadData() {
        val cases = listOf(
            KeyPermanentlyInvalidatedException() to FileKeyProblem.GONE,
            UnrecoverableKeyException("x").apply { initCause(KeyPermanentlyInvalidatedException()) } to FileKeyProblem.GONE,
            IllegalStateException(RuntimeException(KeyPermanentlyInvalidatedException())) to FileKeyProblem.GONE,
            AEADBadTagException() to FileKeyProblem.BAD_DATA,
            IllegalBlockSizeException().apply { initCause(AEADBadTagException()) } to FileKeyProblem.BAD_DATA,
            UserNotAuthenticatedException() to FileKeyProblem.LOCKED,
            KeyStoreException("device locked") to FileKeyProblem.LOCKED,
            IllegalBlockSizeException().apply { initCause(KeyStoreException("x")) } to FileKeyProblem.LOCKED,
            IllegalStateException() to FileKeyProblem.LOCKED,
            java.security.InvalidKeyException() to FileKeyProblem.LOCKED,
            FileKeyException(FileKeyProblem.GONE) to FileKeyProblem.GONE,
        )
        for ((t, want) in cases) {
            assertEquals(t.toString(), want, KeystoreFileKeys.problemOf(t))
            // Thrown by the Keystore when the key is asked for: the same, as a FileKeyException.
            val ks = SoftFileKeystore().apply { failKey = { t as Exception } }
            assertEquals(t.toString(), want, problem { KeystoreFileKeys(ks).unwrap(ByteArray(60), aad) })
        }
    }

    @Test fun aWrapIsSixtyBytesAndEveryWrongOneIsBadData() {
        val k = KeystoreFileKeys(SoftFileKeystore())
        val a = k.wrap(key, aad, create = false)
        val b = k.wrap(key, aad, create = false)
        assertEquals(60, a.size)
        assertFalse(a.contentEquals(b))
        assertArrayEquals(key, k.unwrap(b, aad))
        for (n in listOf(0, 12, 13, 59, 61)) assertEquals("$n", FileKeyProblem.BAD_DATA, problem { k.unwrap(ByteArray(n), aad) })
        for (i in listOf(0, 11, 12, 43, 44, 59)) {
            val c = a.copyOf().also { it[i] = (it[i].toInt() xor 0x40).toByte() }
            assertEquals("$i", FileKeyProblem.BAD_DATA, problem { k.unwrap(c, aad) })
        }
        assertEquals(FileKeyProblem.BAD_DATA, problem { k.unwrap(a, aad + 1) })
        // A key of the wrong size is never wrapped.
        assertEquals(FileKeyProblem.LOCKED, problem { k.wrap(ByteArray(16), aad, create = false) })
    }

    @Test fun deleteRemovesOnlyRobotFilesV1() {
        val ks = SoftFileKeystore()
        ks.keys["robot_memory_v1"] = ks.keys.getValue(KeystoreFileKeys.ALIAS)
        KeystoreFileKeys(ks).delete()
        assertEquals(listOf(KeystoreFileKeys.ALIAS), ks.deleted)
        assertEquals(setOf("robot_memory_v1"), ks.keys.keys)
        ks.failKey = null
        val failing = object : FileKeystore by ks {
            override fun delete(alias: String) = throw KeyStoreException("busy")
        }
        assertEquals(FileKeyProblem.LOCKED, problem { KeystoreFileKeys(failing).delete() })
    }

    /** Core's store on robot_files_v1: a new cabinet makes the key once, files round trip, and the key lost reads as gone. */
    @Test fun theStoreOnTheKeystoresKey() {
        val ks = SoftFileKeystore(present = false)
        val dir = tmp.newFolder("files")
        val s = FileStore(dir, KeystoreFileKeys(ks), "main", space = { 100L shl 30 })
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val e = s.add(ByteArrayInputStream(bytes), FileIntake("lease.pdf", FileSource.SHARE))
        assertEquals(1, ks.generated)
        s.add(ByteArrayInputStream(ByteArray(3)), FileIntake("b.pdf", FileSource.SHARE))
        assertEquals(1, ks.generated)
        assertArrayEquals(bytes, s.readAll(e.id, 1L shl 20))
        ks.keys.clear()
        s.drop()
        assertEquals(IndexLoad.KeyGone, s.load())
        assertEquals(1, ks.generated)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.core.link.LinkPurpose
import app.heyrobot.core.link.LinkRecords
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.LinkStateProblem
import app.heyrobot.core.link.RecordKey
import app.heyrobot.core.seal.KeyException
import app.heyrobot.core.seal.KeyProblem
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
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStoreException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.UnrecoverableKeyException
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Robot to Robot's keys (robot-to-robot-plan.md §2.4, Q3's default; job L2), with Android's own classes (Robolectric) and
 * the Keystore in software ([SoftLinkKeystore]; Robolectric has no AndroidKeyStore, so what only a phone can show, the
 * Keystore refusing robot_inbox_v1 while locked, is R0b's and the Test Bench's): how each key is made, never over an
 * existing alias and only while nothing is sealed under it, the vault's Off, Ready, Locked and Gone states, wrapping while
 * locked and unwrapping only unlocked, Start over, and the exception mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkVaultTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val pairing = "0123456789abcdef0123456789abcdef"

    /**
     * The Keystore in software: AES keys and RSA key pairs by alias. [locked]: the private key refuses (robot_inbox_v1
     * needs the phone unlocked), the AES key still works (robot_link_v1 doesn't, Q3). [failKey]: what asking for any key
     * throws. [generated] counts the keys made; [specs] what they were made with.
     */
    class SoftLinkKeystore : LinkKeystore {
        val secrets = HashMap<String, SecretKey>()
        val pairs = HashMap<String, KeyPair>()
        var locked = false
        var failKey: (() -> Exception)? = null
        var generated = 0
            private set
        val specs = ArrayList<KeyGenParameterSpec>()
        val deleted = ArrayList<String>()

        override fun contains(alias: String): Boolean = alias in secrets || alias in pairs
        override fun secretKey(alias: String): SecretKey? {
            failKey?.let { throw it() }
            return secrets[alias]
        }
        override fun privateKey(alias: String): PrivateKey? {
            failKey?.let { throw it() }
            if (locked && alias in pairs) throw KeyStoreException("locked")
            return pairs[alias]?.private
        }
        override fun publicKey(alias: String): PublicKey? = pairs[alias]?.public
        override fun generateSecret(spec: KeyGenParameterSpec): SecretKey {
            generated++
            specs += spec
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { secrets[spec.keystoreAlias] = it }
        }
        override fun generatePair(spec: KeyGenParameterSpec): KeyPair {
            generated++
            specs += spec
            return (PAIR ?: KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair().also { PAIR = it })
                .also { pairs[spec.keystoreAlias] = it }
        }
        override fun delete(alias: String) {
            deleted += alias
            secrets.remove(alias)
            pairs.remove(alias)
        }

        companion object {
            /** One RSA-3072 pair for every test: making one takes a moment. */
            var PAIR: KeyPair? = null
        }
    }

    private fun problem(block: () -> Unit): KeyProblem? = try {
        block()
        null
    } catch (x: KeyException) {
        x.problem
    }

    @Test fun robotLinkV1IsMadeAsThePlanSays() {
        val s = KeystoreLinkKeys.spec()
        assertEquals("robot_link_v1", s.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, s.purposes)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), s.blockModes.toList())
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_NONE), s.encryptionPaddings.toList())
        assertEquals(256, s.keySize)
        assertTrue(s.isRandomizedEncryptionRequired)
        // Q3's default (a): :link works while the phone is locked, after its first unlock.
        assertFalse(s.isUnlockedDeviceRequired)
        // TEE: not StrongBox (slow for this), and no user authentication.
        assertFalse(s.isStrongBoxBacked)
        assertFalse(s.isUserAuthenticationRequired)
    }

    @Test fun robotInboxV1IsMadeAsThePlanSays() {
        val s = KeystoreInboxKeys.spec()
        assertEquals("robot_inbox_v1", s.keystoreAlias)
        // Decrypt only: wrapping is the public key's, in software.
        assertEquals(KeyProperties.PURPOSE_DECRYPT, s.purposes)
        assertEquals(3072, s.keySize)
        assertEquals(RSAKeyGenParameterSpec.F4, (s.algorithmParameterSpec as RSAKeyGenParameterSpec).publicExponent)
        assertEquals(3072, (s.algorithmParameterSpec as RSAKeyGenParameterSpec).keysize)
        assertEquals(listOf(KeyProperties.DIGEST_SHA256), s.digests.toList())
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP), s.encryptionPaddings.toList())
        assertTrue(s.isUnlockedDeviceRequired)
        assertFalse(s.isStrongBoxBacked)
        assertFalse(s.isUserAuthenticationRequired)
        assertEquals(318, KeystoreInboxKeys.PLAIN_MAX)
        assertEquals(384, KeystoreInboxKeys.WRAPPED)
    }

    @Test fun offThenSetUpThenReady() {
        val ks = SoftLinkKeystore()
        val vault = LinkVault(File(tmp.root, "link"), ks)
        assertEquals(VaultState.Off, vault.state())
        assertTrue(vault.setUp())
        // robot_inbox_v1 now; robot_link_v1 with the first record (the identity :link makes next).
        assertEquals(listOf("robot_inbox_v1"), ks.specs.map { it.keystoreAlias })
        assertEquals(VaultState.Off, vault.state())
        vault.records.write(RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO), byteArrayOf(1, 2, 3))
        assertEquals(listOf("robot_inbox_v1", "robot_link_v1"), ks.specs.map { it.keystoreAlias })
        assertEquals(VaultState.Ready, vault.state())
        // Never set up twice over what is there.
        assertFalse(vault.setUp())
        assertEquals(2, ks.generated)
        assertArrayEquals(byteArrayOf(1, 2, 3), vault.records.read(RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO)))
        // On storage: files/link/state/ and files/link/inbox/, each record sealed.
        assertEquals(File(tmp.root, "link/state"), vault.records.dir)
        assertEquals(File(tmp.root, "link/inbox"), vault.inbox.dir)
        assertEquals(File("/data/x/files/link"), LinkVault.dir(File("/data/x/files")))
    }

    /** §2.4 and Q3: what waits is sealed while locked (the public key, in software); opening it needs the unlock. */
    @Test fun theInboxWrapsWhileLockedAndOpensOnlyUnlocked() {
        val ks = SoftLinkKeystore()
        val vault = LinkVault(File(tmp.root, "link"), ks)
        vault.setUp()
        ks.locked = true
        val id = vault.inbox.put(pairing, 1_761_080_000, "request".toByteArray())
        val x = try { vault.inbox.open(id); null } catch (e: LinkStateException) { e.problem }
        assertEquals(LinkStateProblem.LOCKED, x)
        // robot_link_v1 isn't unlock-required (Q3): the state is still read and written while locked.
        vault.records.write(RecordKey(LinkPurpose.SESSION, pairing), byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), vault.records.read(RecordKey(LinkPurpose.SESSION, pairing)))
        ks.locked = false
        val w = vault.inbox.open(id)!!
        assertEquals(pairing, w.pairing)
        assertArrayEquals("request".toByteArray(), w.request)
        // The wrap with the public key and the unwrap in the "Keystore": RSA-OAEP, SHA-256, MGF1 with SHA-1, one block.
        val keys = KeystoreInboxKeys(ks)
        val block = keys.wrap(ByteArray(32) { 7 })
        assertEquals(384, block.size)
        assertArrayEquals(ByteArray(32) { 7 }, keys.unwrap(block))
        assertEquals(KeyProblem.BAD_DATA, problem { keys.unwrap(block.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }) })
        assertEquals(KeyProblem.BAD_DATA, problem { keys.unwrap(ByteArray(10)) })
        assertTrue(runCatching { keys.wrap(ByteArray(319)) }.isFailure)
    }

    /** A key missing while something is sealed under it is Gone, kept as it is; never made again over it. */
    @Test fun aMissingKeyOverSealedStateIsGone() {
        val ks = SoftLinkKeystore()
        val vault = LinkVault(File(tmp.root, "link"), ks)
        vault.setUp()
        vault.records.write(RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO), byteArrayOf(1))
        vault.inbox.put(pairing, 1, byteArrayOf(2))
        ks.secrets.clear()
        assertEquals(VaultState.Gone(LinkStateProblem.GONE), vault.state())
        assertFalse(vault.setUp())
        val write = try { vault.records.write(RecordKey(LinkPurpose.SESSION, pairing), byteArrayOf(3)); null } catch (e: LinkStateException) { e.problem }
        assertEquals(LinkStateProblem.GONE, write)
        assertFalse(ks.contains("robot_link_v1"))
        assertTrue(vault.records.anything() && vault.inbox.anything())
        // The inbox's key gone with items waiting: Gone too, and never made again over them.
        val ks2 = SoftLinkKeystore()
        val v2 = LinkVault(File(tmp.root, "link2"), ks2)
        v2.setUp()
        v2.records.write(RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO), byteArrayOf(1))
        v2.inbox.put(pairing, 1, byteArrayOf(2))
        ks2.pairs.clear()
        assertEquals(VaultState.Gone(LinkStateProblem.GONE), v2.state())
        assertFalse(v2.inboxReady())
        assertFalse(ks2.contains("robot_inbox_v1"))
        // With nothing waiting under it, a lost robot_inbox_v1 is made again (nothing is lost).
        v2.inbox.deleteAll()
        assertTrue(v2.inboxReady())
        assertTrue(ks2.contains("robot_inbox_v1"))
        assertEquals(VaultState.Ready, v2.state())
    }

    @Test fun lockedAndDamagedStates() {
        val ks = SoftLinkKeystore()
        val vault = LinkVault(File(tmp.root, "link"), ks)
        vault.setUp()
        vault.records.write(RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO), byteArrayOf(1))
        assertEquals(VaultState.Locked, vault.problem(LinkStateException(LinkStateProblem.LOCKED)))
        assertEquals(VaultState.Locked, vault.problem(LinkStateException(LinkStateProblem.IO)))
        assertEquals(VaultState.Newer, vault.problem(LinkStateException(LinkStateProblem.NEWER)))
        assertEquals(VaultState.Gone(LinkStateProblem.DAMAGED), vault.problem(LinkStateException(LinkStateProblem.DAMAGED)))
        assertEquals(VaultState.Gone(LinkStateProblem.GONE), vault.problem(LinkStateException(LinkStateProblem.GONE)))
        // The Keystore busy: Locked, not gone; invalidated: Gone.
        ks.failKey = { KeyStoreException("busy") }
        val busy = try { vault.records.readAll(); null } catch (e: LinkStateException) { e.problem }
        assertEquals(LinkStateProblem.LOCKED, busy)
        ks.failKey = { UnrecoverableKeyException("x").apply { initCause(KeyPermanentlyInvalidatedException()) } }
        val gone = try { vault.records.readAll(); null } catch (e: LinkStateException) { e.problem }
        assertEquals(LinkStateProblem.GONE, gone)
        ks.failKey = null
        assertTrue(vault.records.readAll().isNotEmpty())
    }

    /** F59: what the last open found outranks a Ready on storage: damage tells Settings, and a state that isn't Ready speaks for itself. */
    @Test fun theLastOpenDecidesTheCode() {
        val vault = LinkVault(File(tmp.root, "link"), SoftLinkKeystore())
        val damaged = vault.problem(LinkStateException(LinkStateProblem.DAMAGED))
        assertEquals(LinkIpcProtocol.VAULT_GONE, LinkRuntime.vaultCode(VaultState.Ready, damaged))
        assertEquals(LinkIpcProtocol.VAULT_LOCKED, LinkRuntime.vaultCode(VaultState.Ready, VaultState.Locked))
        assertEquals(LinkIpcProtocol.VAULT_NEWER, LinkRuntime.vaultCode(VaultState.Ready, VaultState.Newer))
        assertEquals(LinkIpcProtocol.VAULT_READY, LinkRuntime.vaultCode(VaultState.Ready, null))
        assertEquals(LinkIpcProtocol.VAULT_OFF, LinkRuntime.vaultCode(VaultState.Off, damaged))
    }

    /** F59: Settings sees that something is sealed from the names alone, in any of the three folders. */
    @Test fun holdsAnythingLooksInEachFolder() {
        val files = tmp.newFolder("files")
        assertFalse(app.heyrobot.core.link.LinkFolders.holdsAnything(files))
        for (d in listOf(LinkVault.STATE, LinkVault.INBOX, LinkVault.LIST_INBOX)) {
            val f = File(LinkVault.dir(files), "$d/x").also { it.parentFile!!.mkdirs(); it.writeText("x") }
            assertTrue(app.heyrobot.core.link.LinkFolders.holdsAnything(files))
            f.delete()
        }
        assertFalse(app.heyrobot.core.link.LinkFolders.holdsAnything(files))
    }

    /** Start over (your tap): everything sealed destroyed, both keys deleted; the only way the keys ever go. */
    @Test fun startOverDeletesEverything() {
        val ks = SoftLinkKeystore()
        val dir = File(tmp.root, "link")
        val vault = LinkVault(dir, ks)
        vault.setUp()
        vault.records.write(RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO), byteArrayOf(1))
        vault.inbox.put(pairing, 1, byteArrayOf(2))
        assertEquals(emptyList<String>(), ks.deleted)
        vault.startOver()
        assertEquals(listOf("robot_link_v1", "robot_inbox_v1"), ks.deleted)
        assertFalse(File(dir, "state").exists() || File(dir, "inbox").exists())
        assertEquals(VaultState.Off, vault.state())
        assertTrue(vault.setUp())
    }

    /** FB5: cut before the state is all gone: still Ready, so :link reads it and finds no identity (F59's warning). Cut after: Off, and setting up again works. */
    @Test fun aStartOverCutPartWayWarnsOrSetsUpAgain() {
        val ks = SoftLinkKeystore()
        val vault = LinkVault(File(tmp.root, "link"), ks)
        vault.setUp()
        val identity = RecordKey(LinkPurpose.IDENTITY, RecordKey.ZERO)
        vault.records.write(identity, byteArrayOf(1))
        repeat(4) { vault.records.write(RecordKey(LinkPurpose.REPLAY, RecordKey.number(it + 1)), byteArrayOf(1)) }
        vault.inbox.put(pairing, 1, byteArrayOf(2))
        var left = 1
        try { vault.records.deleteAll { f -> if (left-- == 0) throw IllegalStateException(); LinkRecords.destroy(f) } } catch (_: IllegalStateException) {}
        assertEquals(VaultState.Ready, vault.state())
        assertEquals(setOf(LinkPurpose.REPLAY), vault.records.readAll().keys.map { it.purpose }.toSet())
        // The records all gone, the inbox and the keys not yet: nothing to warn about, and what waits has no pairing left to go to.
        vault.records.deleteAll()
        assertEquals(VaultState.Off, vault.state())
        assertTrue(vault.setUp())
        vault.records.write(identity, byteArrayOf(1))
        assertEquals(VaultState.Ready, vault.state())
    }

    @Test fun keystoreFailuresMapToTheirProblems() {
        assertEquals(KeyProblem.GONE, problemOf(KeyPermanentlyInvalidatedException()))
        assertEquals(KeyProblem.GONE, problemOf(UnrecoverableKeyException("x").apply { initCause(KeyPermanentlyInvalidatedException()) }))
        assertEquals(KeyProblem.BAD_DATA, problemOf(AEADBadTagException()))
        assertEquals(KeyProblem.BAD_DATA, problemOf(BadPaddingException()))
        assertEquals(KeyProblem.LOCKED, problemOf(KeyStoreException("busy")))
        assertEquals(KeyProblem.LOCKED, problemOf(IllegalStateException()))
        assertEquals(KeyProblem.GONE, problemOf(KeyException(KeyProblem.GONE)))
        // robot_link_v1's own wrap: no key and not asked to make one is GONE; a wrong tag BAD_DATA.
        val keys = KeystoreLinkKeys(SoftLinkKeystore())
        assertEquals(KeyProblem.GONE, problem { keys.wrap(ByteArray(32), byteArrayOf(1), create = false) })
        val w = keys.wrap(ByteArray(32) { 3 }, byteArrayOf(1), create = true)
        assertEquals(60, w.size)
        assertArrayEquals(ByteArray(32) { 3 }, keys.unwrap(w, byteArrayOf(1)))
        assertEquals(KeyProblem.BAD_DATA, problem { keys.unwrap(w, byteArrayOf(2)) })
        assertEquals(KeyProblem.BAD_DATA, problem { keys.unwrap(ByteArray(59), byteArrayOf(1)) })
    }
}

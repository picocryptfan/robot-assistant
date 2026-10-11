package app.heyrobot.sys

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.SecretRead
import app.heyrobot.update.UpdateToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The secrets' callers (the updater's token for GitHubClient and the daily job):
 * a read that fails in a way that may pass keeps the file, its masked form and the Keystore key, and the next read works;
 * only a read that can never work again removes them, and Settings then says so. The Android Keystore isn't in
 * Robolectric, so ordinary AES keys stand in for its keys ([KeystoreSecret.keys]), failing as told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreSecretReadTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class FakeKeystore : SecretKeys {
        val held = HashMap<String, SecretKey>()
        val deleted = mutableListOf<String>()
        /** Thrown by every lookup while set: the Keystore failing. */
        var failure: Throwable? = null

        override fun get(alias: String): SecretKey? {
            failure?.let { throw it }
            return held[alias]
        }

        override fun create(alias: String): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { held[alias] = it }

        override fun delete(alias: String) {
            deleted += alias
            held.remove(alias)
        }
    }

    private val keystore = FakeKeystore()
    private val before = KeystoreSecret.keys

    @Before fun useTheFakeKeystore() {
        KeystoreSecret.keys = keystore
    }

    @After fun restore() {
        KeystoreSecret.keys = before
        secret.delete(ctx)
        UpdateToken.delete(ctx)
    }

    private val claudeKey = "sk-ant-api03-" + "Ab1_-".repeat(18) + "xyzAA"
    /** A secret kept as Ask Claude's key was in 0.4.5. */
    private val secret = KeystoreSecret(HelperKeyFiles.CLAUDE_ALIAS, "claude_key", "claude_key")
    private val token = "github_pat_" + "A1b2_".repeat(16) + "xy"

    /** Failures that may pass: the Keystore busy, restarting or locked, the file unreadable for a moment. */
    private fun transient(): List<Throwable> = listOf(
        KeyStoreException("busy"),
        ProviderException("keystore restarting"),
        UserNotAuthenticatedException(),
        IllegalStateException("keystore"),
        UnrecoverableKeyException("Failed to obtain information about key").initCause(KeyStoreException("system error")),
        IOException("EIO"),
        RuntimeException("unexpected"),
    )

    @Test fun aFailureThatMayPassKeepsEverythingAndTheNextReadWorks() {
        val s = secret
        assertTrue(s.save(ctx, claudeKey, "sk-ant-…xyzAA"))
        val file = s.secret(ctx).readBytes()
        for (t in transient()) {
            keystore.failure = t
            val r = s.read(ctx)
            assertTrue("$t: $r", r is SecretRead.Unavailable)
            // Nothing deleted: the file, its masked form, the Keystore key.
            assertTrue(t.toString(), s.saved(ctx) && s.secret(ctx).readBytes().contentEquals(file))
            assertEquals("sk-ant-…xyzAA", s.masked(ctx))
            assertFalse(s.unreadable(ctx))
            assertTrue(keystore.deleted.isEmpty() && keystore.held.containsKey(HelperKeyFiles.CLAUDE_ALIAS))
            // Once the Keystore answers again, the same key reads.
            keystore.failure = null
            assertEquals(SecretRead.Present(claudeKey), s.read(ctx))
        }
    }

    @Test fun aFailureThatWillHappenAgainRemovesItAndSettingsSaysSo() {
        val s = secret
        val gone = listOf<(FakeKeystore) -> Unit>(
            // Its Keystore key is gone, or was replaced by another.
            { it.held.clear() },
            { it.held[HelperKeyFiles.CLAUDE_ALIAS] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
            // Android says it can never be used again.
            { it.failure = KeyPermanentlyInvalidatedException() },
            { it.failure = UnrecoverableKeyException("User changed or deleted their auth credentials") },
        )
        for (breakIt in gone) {
            keystore.failure = null
            keystore.deleted.clear()
            assertTrue(s.save(ctx, claudeKey, "sk-ant-…xyzAA"))
            breakIt(keystore)
            assertTrue(s.read(ctx) is SecretRead.Invalidated)
            assertFalse(s.saved(ctx))
            assertNull(s.masked(ctx))
            assertTrue(s.unreadable(ctx))
            assertEquals(listOf(HelperKeyFiles.CLAUDE_ALIAS), keystore.deleted)
            assertEquals(SecretRead.Absent, s.read(ctx))
        }
        // A damaged file, whatever the Keystore says.
        keystore.failure = null
        assertTrue(s.save(ctx, claudeKey, "sk-ant-…xyzAA"))
        s.secret(ctx).writeBytes(byteArrayOf(12, 1, 2, 3))
        keystore.failure = KeyStoreException("busy")
        assertEquals(SecretRead.Invalidated("malformed"), s.read(ctx))
        assertTrue(!s.saved(ctx) && s.unreadable(ctx))
    }

    @Test fun theUpdatersTokenIsKeptWhenItCantBeReadJustNow() {
        assertTrue(UpdateToken.save(ctx, token))
        for (t in transient()) {
            keystore.failure = t
            // GitHubClient's read for the daily check, a download, or Check now.
            assertTrue(t.toString(), UpdateToken.read(ctx) is SecretRead.Unavailable)
            assertTrue(UpdateToken.saved(ctx))
            assertEquals("github_pat_…2_xy", UpdateToken.masked(ctx))
            assertFalse(UpdateToken.unreadable(ctx))
            assertTrue(keystore.deleted.isEmpty())
        }
        keystore.failure = null
        assertEquals(SecretRead.Present(token), UpdateToken.read(ctx))
        // Its key gone: removed, and the Updates page says to add it again.
        keystore.held.remove(UpdateToken.ALIAS)
        assertTrue(UpdateToken.read(ctx) is SecretRead.Invalidated)
        assertFalse(UpdateToken.saved(ctx))
        assertTrue(UpdateToken.unreadable(ctx))
        assertEquals(listOf(UpdateToken.ALIAS), keystore.deleted)
    }
}

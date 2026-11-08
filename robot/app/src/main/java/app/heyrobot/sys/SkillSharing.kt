// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.skills.SkillExport
import app.heyrobot.core.skills.SkillShare
import app.heyrobot.core.skills.SkillShareCode
import app.heyrobot.core.skills.SkillSig
import app.heyrobot.core.skills.SkillSigner
import app.heyrobot.core.skills.TrustedKey
import app.heyrobot.core.writeAtomic
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

// MAIN PROCESS (1.3, skills plan S3.4, design §11). Sharing skills: robot_skill_signing, the Android Keystore's P-256 key
// that signs your exports (made at the first need, never exportable, used only here, in the main process); the keys you
// trust (files/robot/skill_keys.json: fingerprints and names, never a private key); a picked or shared `.robotskill`
// read once, at most SkillExport.MAX_BYTES + 1 bytes, and held in this process's memory for the import screen (nothing
// runs from it, nothing of it is written until your tap on Import); SKILL_SHARE in the voice log, codes only.

/** The Keystore calls the signer makes: the phone's AndroidKeyStore, or a software stand-in in the tests. */
interface SkillKeystore {
    /** The key's SubjectPublicKeyInfo, or null when there is no key yet. */
    fun publicKey(): ByteArray?
    fun generate()
    fun privateKey(): PrivateKey?
}

class AndroidSkillKeystore : SkillKeystore {
    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    override fun publicKey(): ByteArray? = store().getCertificate(SkillSigning.ALIAS)?.publicKey?.encoded
    override fun privateKey(): PrivateKey? = store().getKey(SkillSigning.ALIAS, null) as? PrivateKey
    override fun generate() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
            initialize(KeyGenParameterSpec.Builder(SkillSigning.ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE)).setDigests(KeyProperties.DIGEST_SHA256).build())
            generateKeyPair()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore" // l10n: not text (a provider name)
        const val CURVE = "secp256r1" // l10n: not text (a curve's name)
    }
}

object SkillSigning {
    const val ALIAS = "robot_skill_signing" // l10n: not text (a Keystore alias)
    private const val ALG = "SHA256withECDSA" // l10n: not text (a java.security algorithm)

    /** Only the main process signs (plan §4): any other gets nothing. */
    var mainProcess: () -> Boolean = { !android.app.Application.getProcessName().contains(':') }

    /** The signer over [ks]'s key, made now when there is none; null when the Keystore can't give one (no export then). */
    fun signer(ks: SkillKeystore = AndroidSkillKeystore()): SkillSigner? = try {
        check(mainProcess())
        if (ks.publicKey() == null) ks.generate()
        val spki = ks.publicKey()!!
        val key = ks.privateKey()!!
        // A strict P-256 key, or nothing.
        checkNotNull(SkillSig.publicKey(spki))
        object : SkillSigner {
            override val publicKey: ByteArray = spki
            override fun sign(data: ByteArray): ByteArray = Signature.getInstance(ALG).run { initSign(key); update(data); sign() }
        }
    } catch (t: Throwable) {
        RLog.w("skill_signing", t)
        null
    }

    /** This phone's fingerprint (the key made when needed), or null. */
    fun fingerprint(ks: SkillKeystore = AndroidSkillKeystore()): String? = signer(ks)?.let { SkillSig.fingerprint(it.publicKey) }
}

object SkillShareFiles {
    private fun file(ctx: Context): File = File(Paths.dir(ctx), SkillShare.FILE)

    fun trusted(ctx: Context): List<TrustedKey> = try {
        file(ctx).takeIf { it.isFile }?.let { SkillShare.readTrusted(it.readText()) }.orEmpty()
    } catch (t: Exception) {
        RLog.w("skill_keys_read", t)
        emptyList()
    }

    fun saveTrusted(ctx: Context, keys: List<TrustedKey>): Boolean = try {
        writeAtomic(file(ctx), SkillShare.writeTrusted(keys))
        true
    } catch (t: Exception) {
        RLog.w("skill_keys_write", t)
        false
    }

    /** At most [SkillExport.MAX_BYTES] + 1 bytes of [uri] (one more says it's too large), read once; null when it can't be read. */
    fun read(src: SharedSource, uri: Uri): ByteArray? = try {
        src.open(uri, CancellationSignal()).input.use { input -> cap(input, SkillExport.MAX_BYTES + 1) }
    } catch (t: Exception) {
        RLog.w("skill_file_read", t)
        null
    }

    fun cap(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The file the import screen shows next: in this process's memory only, until it's imported or left. */
    @Volatile private var held: ByteArray? = null

    fun hold(bytes: ByteArray) { held = bytes }

    fun held(): ByteArray? = held

    /** Import done or left: the file goes. */
    fun clear() { held = null }

    /** SKILL_SHARE in the main process's voice log: codes only. */
    fun log(ctx: Context, code: SkillShareCode, signed: Int, why: Int) {
        try {
            if (!android.app.Application.getProcessName().contains(':')) VoiceLog.init(Paths.voiceLogMain(ctx))
            VoiceLog.add(VoiceEvent.SKILL_SHARE, code.id, signed, why)
            VoiceLog.flush()
        } catch (t: Throwable) { RLog.w("skill_share_log", t) }
    }
}

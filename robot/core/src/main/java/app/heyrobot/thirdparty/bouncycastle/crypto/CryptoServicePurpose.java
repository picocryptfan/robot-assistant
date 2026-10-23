/*
 * Bouncy Castle 1.81 (bcgit/bc-java at tag r1rv81, core/src/main/java/org/bouncycastle/crypto/CryptoServicePurpose.java), copied into Robot for 1.1's
 * backups (Argon2id), its package renamed from org.bouncycastle to app.heyrobot.thirdparty.bouncycastle. Copyright (c)
 * 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org), under its MIT-style licence: LICENSE;
 * what Robot copied and changed: NOTICE (both in core/src/main/java/app/heyrobot/thirdparty/bouncycastle/).
 */
package app.heyrobot.thirdparty.bouncycastle.crypto;

public enum CryptoServicePurpose
{
    AGREEMENT,
    ENCRYPTION,
    DECRYPTION,
    KEYGEN,
    SIGNING,         // for signatures (and digests)
    VERIFYING,
    AUTHENTICATION,  // for MACs (and digests)
    VERIFICATION,
    PRF,
    ANY
}

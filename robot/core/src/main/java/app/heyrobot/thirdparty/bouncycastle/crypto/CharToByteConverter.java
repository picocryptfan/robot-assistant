/*
 * Bouncy Castle 1.81 (bcgit/bc-java at tag r1rv81, core/src/main/java/org/bouncycastle/crypto/CharToByteConverter.java), copied into Robot for 1.1's
 * backups (Argon2id), its package renamed from org.bouncycastle to app.heyrobot.thirdparty.bouncycastle. Copyright (c)
 * 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org), under its MIT-style licence: LICENSE;
 * what Robot copied and changed: NOTICE (both in core/src/main/java/app/heyrobot/thirdparty/bouncycastle/).
 */
package app.heyrobot.thirdparty.bouncycastle.crypto;

/**
 * Interface for a converter that produces a byte encoding for a char array.
 */
public interface CharToByteConverter
{
    /**
     * Return the type of the conversion.
     *
     * @return a type name for the conversion.
     */
    String getType();

    /**
     * Return a byte encoded representation of the passed in password.
     *
     * @param password the characters to encode.
     * @return a byte encoding of password.
     */
    byte[] convert(char[] password);
}

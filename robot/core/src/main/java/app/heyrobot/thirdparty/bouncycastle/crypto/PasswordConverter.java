/*
 * Bouncy Castle 1.81 (bcgit/bc-java at tag r1rv81, core/src/main/java/org/bouncycastle/crypto/PasswordConverter.java), copied into Robot for 1.1's
 * backups (Argon2id), its package renamed from org.bouncycastle to app.heyrobot.thirdparty.bouncycastle. Copyright (c)
 * 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org), under its MIT-style licence: LICENSE;
 * what Robot copied and changed: NOTICE (both in core/src/main/java/app/heyrobot/thirdparty/bouncycastle/).
 */
package app.heyrobot.thirdparty.bouncycastle.crypto;

/**
 * Standard char[] to byte[] converters for password based derivation algorithms.
 */
public enum PasswordConverter
    implements CharToByteConverter
{
    /**
     * Do a straight char[] to 8 bit conversion.
     */
    ASCII
        {
            public String getType()
            {
                return "ASCII";
            }

            public byte[] convert(char[] password)
            {
                return PBEParametersGenerator.PKCS5PasswordToBytes(password);
            }
        },
    /**
     * Do a char[] conversion by producing UTF-8 data.
     */
    UTF8
        {
            public String getType()
            {
                return "UTF8";
            }

            public byte[] convert(char[] password)
            {
                return PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password);
            }
        },
    /**
     * Do char[] to BMP conversion (i.e. 2 bytes per character).
     */
    PKCS12
        {
            public String getType()
            {
                return "PKCS12";
            }

            public byte[] convert(char[] password)
            {
                return PBEParametersGenerator.PKCS12PasswordToBytes(password);
            }
        };
}

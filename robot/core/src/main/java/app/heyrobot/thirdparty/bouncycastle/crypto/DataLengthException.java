/*
 * Bouncy Castle 1.81 (bcgit/bc-java at tag r1rv81, core/src/main/java/org/bouncycastle/crypto/DataLengthException.java), copied into Robot for 1.1's
 * backups (Argon2id), its package renamed from org.bouncycastle to app.heyrobot.thirdparty.bouncycastle. Copyright (c)
 * 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org), under its MIT-style licence: LICENSE;
 * what Robot copied and changed: NOTICE (both in core/src/main/java/app/heyrobot/thirdparty/bouncycastle/).
 */
package app.heyrobot.thirdparty.bouncycastle.crypto;

/**
 * this exception is thrown if a buffer that is meant to have output
 * copied into it turns out to be too short, or if we've been given 
 * insufficient input. In general this exception will get thrown rather
 * than an ArrayOutOfBounds exception.
 */
public class DataLengthException 
    extends RuntimeCryptoException
{
    /**
     * base constructor.
     */
    public DataLengthException()
    {
    }

    /**
     * create a DataLengthException with the given message.
     *
     * @param message the message to be carried with the exception.
     */
    public DataLengthException(
        String  message)
    {
        super(message);
    }
}

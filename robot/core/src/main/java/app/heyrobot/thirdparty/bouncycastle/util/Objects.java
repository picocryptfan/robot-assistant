/*
 * Bouncy Castle 1.81 (bcgit/bc-java at tag r1rv81, core/src/main/java/org/bouncycastle/util/Objects.java), copied into Robot for 1.1's
 * backups (Argon2id), its package renamed from org.bouncycastle to app.heyrobot.thirdparty.bouncycastle. Copyright (c)
 * 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org), under its MIT-style licence: LICENSE;
 * what Robot copied and changed: NOTICE (both in core/src/main/java/app/heyrobot/thirdparty/bouncycastle/).
 */
package app.heyrobot.thirdparty.bouncycastle.util;

public class Objects
{
    public static boolean areEqual(Object a, Object b)
    {
        return a == b || (null != a && null != b && a.equals(b));
    }

    public static int hashCode(Object obj)
    {
        return null == obj ? 0 : obj.hashCode();
    }
}

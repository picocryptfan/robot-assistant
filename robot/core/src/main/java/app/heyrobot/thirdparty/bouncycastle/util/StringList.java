/*
 * Bouncy Castle 1.81 (bcgit/bc-java at tag r1rv81, core/src/main/java/org/bouncycastle/util/StringList.java), copied into Robot for 1.1's
 * backups (Argon2id), its package renamed from org.bouncycastle to app.heyrobot.thirdparty.bouncycastle. Copyright (c)
 * 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org), under its MIT-style licence: LICENSE;
 * what Robot copied and changed: NOTICE (both in core/src/main/java/app/heyrobot/thirdparty/bouncycastle/).
 */
package app.heyrobot.thirdparty.bouncycastle.util;

/**
 * An interface defining a list of strings.
 */
public interface StringList
    extends Iterable<String>
{
    /**
     * Add a String to the list.
     *
     * @param s the String to add.
     * @return true
     */
    boolean add(String s);

    /**
     * Get the string at index index.
     *
     * @param index the index position of the String of interest.
     * @return the String at position index.
     */
    String get(int index);

    int size();

    /**
     * Return the contents of the list as an array.
     *
     * @return an array of String.
     */
    String[] toStringArray();

    /**
     * Return a section of the contents of the list. If the list is too short the array is filled with nulls.
     *
     * @param from the initial index of the range to be copied, inclusive
     * @param to the final index of the range to be copied, exclusive.
     * @return an array of length to - from
     */
    String[] toStringArray(int from, int to);
}

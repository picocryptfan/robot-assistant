// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.feedback.ReportKey

// MAIN PROCESS. RP5: the key a report is sealed to: core's ReportKey.KEY, or while it is empty a debug build's throwaway
// (ReportTestKey, src/debug only); null: no key, so the card hides Send and the screenshot and offers plain text only.
object ReportKeys {
    private const val TEST_KEY = "app.heyrobot.sys.ReportTestKey" // l10n: not text (a class name)

    fun key(): ByteArray? = ReportKey.decode(ReportKey.KEY) ?: testKey()

    internal fun testKey(): ByteArray? = try {
        ReportKey.decode(Class.forName(TEST_KEY).getField("KEY").get(null) as? String) // l10n: not text (a field name)
    } catch (_: ReflectiveOperationException) { null } catch (_: LinkageError) { null }
}

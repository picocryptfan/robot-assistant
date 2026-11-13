// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

// Pure (1.4, report-a-problem-plan.md "What's new" 3 and 5). The report's fixed header, written and read back. v2 adds
// "Feature:", "Problem:" (ReportSort, changed by you on the card) and "Parts:", the parts you ticked; v1 (0.7's Send
// feedback, FeedbackReport) still reads, its parts taken from the diagnostics it lists. The header stays English: the
// developer and the PC tool (tools/reports, "open" and "group") read it.

/** A part of a report, each only when you tick it. [header]: its word on the "Parts:" line. */
enum class ReportPart(val header: String) {
    SAID("what you said"), // l10n: not text (the report's header: English)
    ANSWER("my answer"), // l10n: not text (the report's header: English)
    ACTIONS("what I did"), // l10n: not text (the report's header: English)
    FACTS("facts"), // l10n: not text (the report's header: English)
    PROBLEM("problem report"), // l10n: not text (the report's header: English)
    SETTINGS("settings"), // l10n: not text (the report's header: English)
    VOICE_LOG("voice log"), // l10n: not text (the report's header: English)
    UPDATER("updater"), // l10n: not text (the report's header: English)
    ONLINE("what went online"), // l10n: not text (the report's header: English)
    SCREENSHOT("screenshot"), // l10n: not text (the report's header: English)
    ;

    companion object {
        fun of(s: String?): ReportPart? = s?.trim()?.let { t -> entries.firstOrNull { it.header.equals(t, true) || it.name.equals(t, true) } }

        fun of(d: Diagnostic): ReportPart = when (d) {
            Diagnostic.PROBLEM -> PROBLEM
            Diagnostic.VOICE_LOG -> VOICE_LOG
            Diagnostic.SETTINGS -> SETTINGS
            Diagnostic.UPDATER -> UPDATER
            Diagnostic.ONLINE -> ONLINE
        }
    }
}

/**
 * A report's header: [version] 1 or 2, the kind (null if a newer Robot wrote one this version doesn't know), the RFB-
 * reference, the facts, and for v2 the feature, the problem and the parts ticked (null or empty when unknown).
 */
data class ReportHeader(
    val version: Int,
    val kind: FeedbackKind?,
    val ref: String,
    val facts: FeedbackFacts,
    val feature: ReportFeature? = null,
    val problem: ReportProblem? = null,
    val parts: Set<ReportPart> = emptySet(),
) {
    /** What v2 adds to a report's header. */
    data class V2(val feature: ReportFeature, val problem: ReportProblem, val parts: Set<ReportPart>)

    companion object {
        const val V1 = "ROBOT FEEDBACK v1" // l10n: not text (the report's header: English)
        const val V2_LINE = "ROBOT FEEDBACK v2" // l10n: not text (the report's header: English)
        private const val LANGUAGE = " · Language: " // l10n: not text (the report's header: English)
        private const val DIAGNOSTICS = "Diagnostics (ticked by the tester):" // l10n: not text (the report's header: English)

        /** The header's lines, each ending in a newline, without the separator under them: v1 when [v2] is null, as 0.7 wrote it. */
        fun write(kind: FeedbackKind, ref: String, facts: FeedbackFacts, v2: V2? = null): String = buildString {
            append(if (v2 == null) V1 else V2_LINE).append('\n')
            append("Kind: ").append(kind.header).append('\n') // l10n: not text (the report's header: English)
            append("Ref: ").append(ref).append('\n') // l10n: not text (the report's header: English)
            if (v2 != null) {
                append("Feature: ").append(v2.feature.header).append('\n') // l10n: not text (the report's header: English)
                append("Problem: ").append(v2.problem.header).append('\n') // l10n: not text (the report's header: English)
                val parts = ReportPart.entries.filter { it in v2.parts }
                append("Parts: ").append(if (parts.isEmpty()) "none" else parts.joinToString(", ") { it.header }).append('\n') // l10n: not text (the report's header: English)
            }
            append("Robot: ").append(FeedbackFacts.line(facts.robot, 120)).append('\n') // l10n: not text (the report's header: English)
            append("Phone: ").append(FeedbackFacts.line(facts.phone, 200)).append('\n') // l10n: not text (the report's header: English)
            if (facts.chip.isNotEmpty()) append("Chip: ").append(FeedbackFacts.line(facts.chip, 120)).append('\n') // l10n: not text (the report's header: English)
            append("Model: ").append(FeedbackFacts.line(facts.model, 200)).append('\n') // l10n: not text (the report's header: English)
            append("Look: ").append(FeedbackFacts.line(facts.look, 20)).append(LANGUAGE).append(FeedbackFacts.line(facts.language, 24)).append('\n') // l10n: not text (the report's header: English)
        }

        /**
         * The header of a whole report [text] (v1 or v2), or null when it isn't one: no known first line or no valid
         * reference. Lines it doesn't know are skipped, so a later Robot's extra lines still read.
         */
        fun read(text: String): ReportHeader? {
            val lines = text.replace("\r\n", "\n").split('\n')
            val version = when (lines.firstOrNull()?.trim()) { V1 -> 1; V2_LINE -> 2; else -> return null }
            val head = lines.drop(1).takeWhile { it.trim() != FeedbackReport.SEPARATOR }
            val fields = head.mapNotNull { l -> l.indexOf(": ").takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 2).trim() } }.toMap()
            val ref = fields["Ref"]?.takeIf { FeedbackRef.valid(it) } ?: return null // l10n: not text (a header key)
            val look = fields["Look"].orEmpty() // l10n: not text (a header key)
            val facts = FeedbackFacts(
                robot = fields["Robot"].orEmpty(), // l10n: not text (a header key)
                phone = fields["Phone"].orEmpty(), // l10n: not text (a header key)
                model = fields["Model"].orEmpty(), // l10n: not text (a header key)
                look = look.substringBefore(LANGUAGE),
                language = if (LANGUAGE in look) look.substringAfter(LANGUAGE) else "",
                chip = fields["Chip"].orEmpty(), // l10n: not text (a header key)
            )
            val kind = FeedbackKind.entries.firstOrNull { it.header == fields["Kind"] } // l10n: not text (a header key)
            if (version == 1) return ReportHeader(1, kind, ref, facts, parts = v1Parts(lines))
            val parts = fields["Parts"].orEmpty().split(',').mapNotNull { ReportPart.of(it) }.toSet() // l10n: not text (a header key)
            return ReportHeader(2, kind, ref, facts, ReportFeature.of(fields["Feature"]), ReportProblem.of(fields["Problem"]), parts) // l10n: not text (header keys)
        }

        /** v1 has no "Parts:": the diagnostics it lists, by their bullets' English titles. */
        private fun v1Parts(lines: List<String>): Set<ReportPart> {
            val start = lines.indexOfFirst { it.trim() == DIAGNOSTICS }
            if (start < 0) return emptySet()
            val titles = Diagnostic.entries.associateBy { it.title }
            return lines.drop(start + 1).takeWhile { it.trim() != FeedbackReport.SEPARATOR }
                .mapNotNull { l -> if (l.startsWith("• ")) titles[l.removePrefix("• ").trim()]?.let { ReportPart.of(it) } else null }.toSet()
        }
    }
}

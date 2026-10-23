// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Langs
import java.time.LocalDate
import java.time.ZoneId

// Pure. 1.4, Robot Files (files-1.4-plan.md §7.2; job F12): the tax pack and the expense report. "Make my 2026 tax pack"
// (FilesRouter) or Files › Tax pack shows what goes; only your tap on Export, then the folder you pick in Android's folder
// picker, copies it out (the app's FilesExport). Nothing goes by voice alone, nothing while the phone is locked, and a
// file's content never starts one: no model tool exports, and the panel's card only opens the page.
//
//   - Tax pack: slips (by their tax year, the `year` field: a 2025 T4 that came in February 2026 is in 2025's pack), and
//     receipts and documents tagged `tax` or kept in Taxes (by their own date, else when they came in).
//   - Expense report: files tagged `business`, by their date.
//   - Never a dangerous file, never one whose bytes failed their check (counted, left out).
//   - A table of their details goes with them (a CSV, UTF-8): values as Robot's code checked them in the index (FileFields;
//     the model never writes a value), a total that needs checking left empty and said so, never summed. A text cell that a
//     spreadsheet could take for a formula (=, +, -, @ first) starts with an apostrophe; numbers stay numbers.

/** Which export: the tax pack or the expense report (`business` files). */
enum class PackKind { TAX, EXPENSES }

/** What goes in [kind]'s pack for [year]: [files], oldest first, and [damaged] left out. */
data class Pack(val kind: PackKind, val year: Int, val files: List<FileEntry>, val damaged: Int) {
    val empty: Boolean get() = files.isEmpty()
    override fun toString(): String = "Pack($kind, $year, ${files.size}, $damaged)" // l10n: not text (toString for logs and tests)
}

/** The table's words in the menus' language: its column names and a kind's noun ("Receipt"). */
class PackWords(val columns: List<String>, val kind: (FileKind) -> String, val drawer: (String) -> String, val checkTotal: String, val notCopied: String) {
    init { require(columns.size == FilePack.COLUMNS) }
}

object FilePack {
    /** The table's columns: file, kind, date, merchant, total, currency, GST, HST, PST, QST, slip, tax year, drawer, note. */
    const val COLUMNS = 14

    /** The taxes the table gives a column each (FileFields' canonical names: fields "tax_gst" and so on). */
    val TAXES = listOf("gst", "hst", "pst", "qst") // l10n: not text (field codes)

    /** Whether [e] goes in a pack of [kind] (any year). */
    fun belongs(e: FileEntry, kind: PackKind): Boolean {
        if (e.kind == FileKind.DANGEROUS) return false
        return when (kind) {
            PackKind.TAX -> e.kind == FileKind.TAX_SLIP || FileTags.TAX in e.tags || e.drawer == Drawers.TAXES
            PackKind.EXPENSES -> FileTags.BUSINESS in e.tags
        }
    }

    /** The year [e] counts for: a slip's tax year, else its own date's, else when it came in. */
    fun yearOf(e: FileEntry, zone: ZoneId = ZoneId.systemDefault()): Int =
        e.fields["year"]?.value?.toIntOrNull()?.takeIf { e.kind == FileKind.TAX_SLIP && it in 1900..2200 } ?: FileViews.day(e, zone).year

    /** The year a pack is for when you don't say: last year, the one tax season files. */
    fun defaultYear(today: LocalDate): Int = today.year - 1

    /** The years [idx] has files for in [kind]'s pack, newest first. */
    fun years(idx: FileIndex, kind: PackKind, zone: ZoneId = ZoneId.systemDefault()): List<Int> =
        idx.entries.filter { belongs(it, kind) }.map { yearOf(it, zone) }.distinct().sortedDescending()

    /** [kind]'s pack for [year] from [idx] (a travel-mode view leaves hidden drawers out: TravelMode.shown). */
    fun of(idx: FileIndex, kind: PackKind, year: Int, zone: ZoneId = ZoneId.systemDefault()): Pack {
        val all = idx.entries.filter { belongs(it, kind) && yearOf(it, zone) == year }
        val ok = all.filter { !it.damaged }.sortedWith(compareBy<FileEntry> { FileViews.day(it, zone) }.thenBy { it.from.at })
        return Pack(kind, year, ok, all.size - ok.size)
    }

    /** The folder an export makes in the one you pick: "Robot tax pack 2026", « Dossier d'impôts 2026 de Robot ». */
    fun folder(kind: PackKind, year: Int, langs: Langs): String = FileOut.name(when (kind) {
        PackKind.TAX -> FilesStrings.packFolderTax("year" to year.toString())
        PackKind.EXPENSES -> FilesStrings.packFolderExpenses("year" to year.toString())
    }.render(langs), app.heyrobot.core.parse.FileType.UNKNOWN)

    /** [names] made unique, in order: a second "Costco.pdf" is "Costco (2).pdf" (case aside, as most folders compare). */
    fun unique(names: List<String>): List<String> {
        val seen = HashSet<String>()
        return names.map { n ->
            val dot = n.lastIndexOf('.').takeIf { it > 0 } ?: n.length
            var out = n
            var k = 1
            while (!seen.add(out.lowercase())) out = n.substring(0, dot) + " (${++k})" + n.substring(dot) // l10n: not text (a copy's number)
            out
        }
    }

    /**
     * The table: a header in [words]' language, then one row per file of [pack] in its order. [copied]: each file's name in
     * the folder, or null when it wasn't copied (the note says so).
     */
    fun csv(pack: Pack, copied: Map<FileId, String?>, words: PackWords, zone: ZoneId = ZoneId.systemDefault()): String {
        val b = StringBuilder("﻿") // A byte-order mark: spreadsheets then read the accents as UTF-8.
        row(b, words.columns.map { text(it) })
        for (e in pack.files) {
            fun f(k: String) = e.fields[k]?.value
            val check = f("check") == "total" // l10n: not text (a field's code)
            val name = copied[e.id]
            val notes = listOfNotNull(if (check) words.checkTotal else null, if (name == null) words.notCopied else null)
            row(b, listOf(
                text(name ?: e.name),
                text(words.kind(e.kind)),
                (FileViews.dateField(e, "date") ?: FileViews.dateField(e, "due") ?: FileViews.day(e, zone)).toString(),
                text(f("merchant").orEmpty()),
                if (check) "" else number(f("total")),
                text(f("currency").orEmpty()),
            ) + TAXES.map { number(f("tax_$it")) } + listOf( // l10n: not text (a field's code)
                text(f("slip").orEmpty()),
                if (e.kind == FileKind.TAX_SLIP) f("year")?.toIntOrNull()?.toString().orEmpty() else "",
                text(e.drawer?.let(words.drawer).orEmpty()),
                text(notes.joinToString("; ")),
            ))
        }
        return b.toString()
    }

    /** An amount as Robot keeps it ("84.12"), or empty when it isn't one. */
    private fun number(v: String?): String = v?.let(FileFields::amountOf)?.let(FileFields::plain).orEmpty()

    /** A text cell that can't run as a formula in a spreadsheet. */
    internal fun text(v: String): String = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v

    private fun row(b: StringBuilder, cells: List<String>) {
        cells.joinTo(b, ",") { c -> if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' } || c != c.trim()) "\"" + c.replace("\"", "\"\"") + "\"" else c }
        b.append("\r\n")
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * LS4's desk run (lists-plan.md §3.5, §8.3), scored by Robot's own word check. The runs are test resource
 * lists/split-desk.tsv (200, English and Québec French, 80 of them one item); the desk model's raw answers, asked through
 * ListSplit's prompt by tools/lists/desk_split.py, are lists/split-desk-replies.jsonl. Each run must be one piece code asks
 * the model about. The numbers are printed for the job's report: what the panel would add for each run.
 */
class ListSplitDeskTest {
    private class Run(val lang: String, val run: String, val items: List<String>)

    private val runs: List<Run> = javaClass.getResourceAsStream("/lists/split-desk.tsv")!!.bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { l -> l.split('\t').let { Run(it[0], it[1], it[2].split(" | ")) } }

    /** An item as the list would keep it: its words without article or quantity, folded. */
    private fun kept(s: String) = ListsText.fold(ItemText.item(s).text)

    @Test fun everyRunIsOnePieceTheModelIsAskedAbout() {
        assertEquals(200, runs.size)
        assertEquals(100, runs.count { it.lang == "fr" })
        assertEquals(80, runs.count { it.items.size == 1 })
        for (r in runs) {
            assertEquals(r.run, listOf(r.run), ItemText.split(r.run))
            assertTrue(r.run, ListSplit.wants(r.run))
            // What a person meant is a split the word check accepts.
            val meant = ListSplit.accept(r.run, org.json.JSONArray(r.items).toString())
            assertEquals(r.run, r.items.map(::kept), meant?.map(::kept))
        }
    }

    @Test fun theDeskModelsAnswers() {
        val file = javaClass.getResourceAsStream("/lists/split-desk-replies.jsonl")
        assumeTrue("no desk replies recorded", file != null)
        val replies = file!!.bufferedReader().readLines().filter { it.isNotBlank() }.map(::JSONObject).associateBy { it.getString("run") }
        assertEquals(runs.size, replies.size)
        val out = StringBuilder("LS4 desk run of LIST_SPLIT (lang, kind: counts)\n")
        for (lang in listOf("en", "fr", "all")) for (several in listOf(true, false)) {
            val set = runs.filter { (lang == "all" || it.lang == lang) && (it.items.size > 1) == several }
            var right = 0; var wrong = 0; var refused = 0; var none = 0
            val ms = ArrayList<Long>()
            for (r in set) {
                val rep = replies.getValue(r.run)
                ms += rep.getLong("ms")
                val answer = rep.getString("raw").takeIf { !rep.optBoolean("limit", false) }?.trim()?.takeIf { it.isNotEmpty() }
                val got = ListSplit.accept(r.run, answer)
                // What the panel adds: the accepted items, or the run as one item.
                val added = (got ?: listOf(r.run)).map(::kept)
                when {
                    answer == null -> none++
                    got == null -> refused++
                    added == r.items.map(::kept) -> right++
                    else -> wrong++
                }
            }
            ms.sort()
            out.append("$lang ${if (several) "several items" else "one item"} (${set.size}): accepted and right $right, accepted and wrong $wrong, " +
                "refused by the check $refused, no answer $none; median ${ms.getOrNull(ms.size / 2)} ms\n")
        }
        println(out)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › About › Licences: the component list's reader (the app's LicencesTest checks the real list and its files). */
class LicenceBookTest {
    private val sample = """
        {"format":1,
         "robot":{"name":"Robot","licence":"GPL-3.0-only","copyright":"Copyright (C) 2026 the Robot authors","text":"licenses/GPL-3.0.txt",
                  "source":"https://example.org/robot","sourceTag":"https://example.org/robot/tree/v{version}",
                  "sourceBundle":"https://example.org/robot/releases/download/v{version}/robot-{version}-third-party-sources.tar.gz"},
         "licences":{"Apache-2.0":{"name":"Apache License 2.0","text":"licenses/Apache-2.0.txt","url":"https://www.apache.org/licenses/LICENSE-2.0"},
                     "MIT":{"name":"MIT License"},
                     "ODbL-1.0":{"name":"Open Database License 1.0","url":"https://opendatacommons.org/licenses/odbl/1-0/"}},
         "components":[
          {"id":"a","name":"Alpha","version":"1.0","licence":"Apache-2.0","copyright":"Copyright A","url":"https://a.example","where":"app",
           "maven":["androidx.*:*","org.example:alpha"],"notices":[{"title":"Alpha's notices","file":"licenses/notices/alpha.txt"}]},
          {"id":"b","name":"Beta","version":"2","licence":"MIT","copyright":"Copyright B","url":"https://b.example","where":"app","text":"licenses/MIT-b.txt",
           "match":["Beta's other name"],"note":"Does things."},
          {"id":"m","name":"Map data","version":"service","licence":"ODbL-1.0","copyright":"© contributors","url":"https://m.example","where":"service"}
         ]}
    """.trimIndent()

    @Test fun readsTheListAndWhereEachTextIs() {
        val b = LicenceBook.parse(sample)
        assertEquals(emptyList<String>(), b.problems())
        assertEquals("https://example.org/robot/releases/download/v1.0.0/robot-1.0.0-third-party-sources.tar.gz", b.robot.bundleAt("1.0.0"))
        assertEquals("https://example.org/robot/tree/v0.4.3", b.robot.sourceAt("0.4.3"))
        val alpha = b.byId("a")!!
        assertEquals(LicenceWhere.APP, alpha.where)
        assertEquals("licenses/Apache-2.0.txt", b.textFile(alpha))
        assertEquals(listOf(LicenceFile("Alpha's notices", "licenses/notices/alpha.txt")), alpha.notices)
        assertEquals("licenses/MIT-b.txt", b.textFile(b.byId("b")!!))
        assertEquals(listOf("Beta's other name"), b.byId("b")!!.match)
        assertEquals("Does things.", b.byId("b")!!.note)
        assertNull(b.textFile(b.byId("m")!!))
        assertEquals(listOf("a", "b"), b.section(LicenceWhere.APP).map { it.id })
        assertEquals(listOf("m"), b.section(LicenceWhere.SERVICE).map { it.id })
        assertEquals(setOf("licenses/GPL-3.0.txt", "licenses/Apache-2.0.txt", "licenses/MIT-b.txt", "licenses/notices/alpha.txt"), b.files())
    }

    @Test fun mavenArtifactsFindTheirPart() {
        val b = LicenceBook.parse(sample)
        assertEquals("a", b.forArtifact("androidx.compose.ui", "ui-android")?.id)
        assertEquals("a", b.forArtifact("org.example", "alpha")?.id)
        assertNull(b.forArtifact("org.example", "alphabet"))
        assertNull(b.forArtifact("androidx", "core"))
        assertNull(b.forArtifact("com.example", "x"))
        assertTrue(LicenceBook.matches("com.fasterxml.jackson.core:*", "com.fasterxml.jackson.core", "jackson-core"))
        assertFalse(LicenceBook.matches("com.fasterxml:classmate", "com.fasterxml", "classmates"))
        assertFalse(LicenceBook.matches("nonsense", "a", "b"))
    }

    @Test fun aBrokenListSaysWhat() {
        fun problems(edit: (String) -> String) = LicenceBook.parse(edit(sample)).problems()
        // Something Robot ships without its licence text.
        assertEquals(listOf("b: no licence text"), problems { it.replace(""","text":"licenses/MIT-b.txt"""", "") })
        assertEquals(listOf("a: licence GPL-9 isn't in the licences", "a: no licence text"), problems { it.replace(""""licence":"Apache-2.0","copyright":"Copyright A"""", """"licence":"GPL-9","copyright":"Copyright A"""") })
        assertEquals(listOf("id a twice"), problems { it.replace(""""id":"b"""", """"id":"a"""") })
        assertEquals(listOf("Robot's source links need {version}"), problems { it.replace("tree/v{version}", "tree/main") })
        assertEquals(listOf("m: Maven artifacts are for parts in the app"), problems { it.replace(""""where":"service"}""", """"where":"service","maven":["x:y"]}""") })
        assertEquals(listOf("../x.txt isn't under licenses/"), problems { it.replace("licenses/MIT-b.txt", "../x.txt") })
        for (bad in listOf("", "[]", """{"format":2}""", sample.replace(""""where":"app","text"""", """"where":"moon","text""""))) {
            assertTrue(bad, runCatching { LicenceBook.parse(bad) }.exceptionOrNull() is IllegalArgumentException ||
                runCatching { LicenceBook.parse(bad) }.exceptionOrNull() is org.json.JSONException)
        }
    }

    @Test fun theNoticeSaysFreeAndNoWarranty() {
        assertTrue(LicenceBook.NOTICE.contains("GNU General Public License") && LicenceBook.NOTICE.contains("version 3"))
        assertTrue(LicenceBook.NOTICE.contains("WITHOUT ANY WARRANTY"))
    }

    @Test fun longTextsComeInPiecesThatAddUpToTheText() {
        val para = (1..30).joinToString("\n") { "Line $it of a paragraph, long enough to count." }
        val text = (1..40).joinToString("\n\n") { "Paragraph $it\n$para" }
        val pieces = LicenceBook.chunks(text, 4_000)
        assertTrue(pieces.size > 10)
        assertTrue(pieces.all { it.length <= 4_000 })
        // Only the blank lines where pieces meet are left out.
        assertEquals(text.replace("\n", ""), pieces.joinToString("") { it.replace("\n", "") })
        assertEquals(listOf("short"), LicenceBook.chunks("short"))
        // A line longer than a piece is cut, nothing lost.
        val long = "x".repeat(9_500)
        assertEquals(listOf(4_000, 4_000, 1_500), LicenceBook.chunks(long, 4_000).map { it.length })
        assertEquals(listOf("a\nb"), LicenceBook.chunks("a\r\nb"))
    }
}

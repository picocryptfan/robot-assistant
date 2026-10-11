package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject

// Pure. Settings › About › Licences (0.4.5): Robot's own GPL-3.0 notice, where its source is, and every third-party part with
// its version, licence, copyright line, notices and full licence text. One data file holds the list,
// app/src/main/assets/licenses/components.json, next to the texts it names; the app's LicencesTest keeps it in step with
// THIRD_PARTY.md, with the libraries the release build packs and with the files in assets/licenses/.

/** Where a part is: in the APK, downloaded when you ask, an online service, or only on the build machine. */
enum class LicenceWhere(val id: String, val title: String) {
    APP("app", "In the app"),
    DOWNLOAD("download", "Downloaded when you ask"),
    SERVICE("service", "Online services Robot asks, when you ask"),
    BUILD("build", "Used only to build and test Robot (not in the app)");

    companion object {
        fun of(id: String): LicenceWhere? = entries.firstOrNull { it.id == id }
    }
}

/** A licence by its SPDX id: its name, the asset with its full text (null: each part brings its own, as MIT does), a link. */
data class LicenceInfo(val id: String, val name: String, val text: String?, val url: String?)

/** A notice or licence file a part ships (an asset path under licenses/) and what to call it. */
data class LicenceFile(val title: String, val file: String)

data class ThirdPartyComponent(
    val id: String,
    val name: String,
    val version: String,
    /** An SPDX id, a key of [LicenceBook.licences]. */
    val licence: String,
    /** The copyright or attribution line, as the part states it. */
    val copyright: String,
    val url: String,
    val where: LicenceWhere,
    /** Its own licence text (an MIT or BSD text carries its copyright line); null: its licence's shared text. */
    val text: String?,
    val notices: List<LicenceFile>,
    /** The Maven artifacts it covers, "group:artifact" with * wildcards ("androidx.*:*"). */
    val maven: List<String>,
    /** Other names THIRD_PARTY.md uses for it (a heading names it so). */
    val match: List<String>,
    /** What it does in Robot, or the attribution its licence asks for. */
    val note: String?,
)

/** Robot itself: its licence, copyright line and where its source is ("{version}" stands for the version). */
data class RobotLicence(
    val name: String,
    val licence: String,
    val copyright: String,
    val text: String,
    val source: String,
    val sourceTag: String,
    val sourceBundle: String,
) {
    /** The source of this version (its tag). */
    fun sourceAt(version: String) = sourceTag.replace(VERSION, version)

    /** The release asset with the source of the GPL native code and what it links (tools/make_source_bundle.sh). */
    fun bundleAt(version: String) = sourceBundle.replace(VERSION, version)

    companion object {
        const val VERSION = "{version}"
    }
}

data class LicenceBook(val robot: RobotLicence, val licences: Map<String, LicenceInfo>, val components: List<ThirdPartyComponent>) {
    fun byId(id: String): ThirdPartyComponent? = components.firstOrNull { it.id == id }

    fun section(w: LicenceWhere): List<ThirdPartyComponent> = components.filter { it.where == w }

    fun licenceOf(c: ThirdPartyComponent): LicenceInfo? = licences[c.licence]

    /** The full licence text to show for [c]: its own, else its licence's (null: none ships, a service's or a build tool's). */
    fun textFile(c: ThirdPartyComponent): String? = c.text ?: licences[c.licence]?.text

    /** Every asset this list names (texts and notices), each once. */
    fun files(): Set<String> = (listOf(robot.text) + licences.values.mapNotNull { it.text } + components.flatMap { listOfNotNull(it.text) + it.notices.map(LicenceFile::file) }).toSet()

    /** The part a Maven artifact belongs to ("androidx.compose.ui", "ui"), or null: then it needs an entry. */
    fun forArtifact(group: String, artifact: String): ThirdPartyComponent? =
        components.firstOrNull { c -> c.maven.any { matches(it, group, artifact) } }

    /** What is wrong with the list (the app's LicencesTest fails on any): one line each. */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        components.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { out += "id $it twice" }
        components.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { out += "name $it twice" }
        if (!robot.sourceBundle.contains(RobotLicence.VERSION) || !robot.sourceTag.contains(RobotLicence.VERSION)) out += "Robot's source links need {version}"
        if (!robot.source.startsWith("https://")) out += "Robot's source isn't an https link"
        for (c in components) {
            if (c.licence !in licences) out += "${c.id}: licence ${c.licence} isn't in the licences"
            if (c.name.isBlank() || c.version.isBlank() || c.copyright.isBlank()) out += "${c.id}: a name, version and copyright line are needed"
            if (!c.url.startsWith("https://")) out += "${c.id}: its link isn't https"
            // What Robot distributes (in the APK, or downloaded by it) comes with its full licence text.
            if ((c.where == LicenceWhere.APP || c.where == LicenceWhere.DOWNLOAD) && c.licence != OWN_FILE && textFile(c) == null) {
                out += "${c.id}: no licence text"
            }
            if (c.where != LicenceWhere.APP && c.maven.isNotEmpty()) out += "${c.id}: Maven artifacts are for parts in the app"
            for (n in c.notices) if (n.title.isBlank()) out += "${c.id}: a notice without a title"
        }
        for (f in files()) if (!f.startsWith("licenses/") || ".." in f) out += "$f isn't under licenses/"
        return out
    }

    companion object {
        /** The list, in the app's assets. */
        const val FILE = "licenses/components.json"
        /** Your own model file's licence: whatever the file's is. */
        const val OWN_FILE = "LicenseRef-own-file"

        /**
         * The GPL's Appropriate Legal Notices (its section 0 and 5(d)): free software, no warranty, and where the licence
         * is (the Licences screen opens it).
         */
        const val NOTICE = "Robot is free software: you can redistribute it and/or modify it under the terms of the GNU General " +
            "Public License as published by the Free Software Foundation, version 3. Robot is distributed in the hope that it will " +
            "be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A " +
            "PARTICULAR PURPOSE. See the GNU General Public License for more details."

        /** Throws IllegalArgumentException when [json] isn't the list. */
        fun parse(json: String): LicenceBook {
            val o = try { JSONObject(json) } catch (e: Exception) { throw IllegalArgumentException("not JSON", e) }
            require(o.optInt("format") == 1) { "unknown format" }
            val r = o.getJSONObject("robot")
            val robot = RobotLicence(r.getString("name"), r.getString("licence"), r.getString("copyright"), r.getString("text"),
                r.getString("source"), r.getString("sourceTag"), r.getString("sourceBundle"))
            val ls = o.getJSONObject("licences")
            val licences = ls.keys().asSequence().associateWith { k ->
                val l = ls.getJSONObject(k)
                LicenceInfo(k, l.getString("name"), l.optString("text").ifEmpty { null }, l.optString("url").ifEmpty { null })
            }
            val a = o.getJSONArray("components")
            val components = (0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                val id = c.getString("id")
                ThirdPartyComponent(
                    id = id, name = c.getString("name"), version = c.getString("version"), licence = c.getString("licence"),
                    copyright = c.getString("copyright"), url = c.getString("url"),
                    where = LicenceWhere.of(c.getString("where")) ?: throw IllegalArgumentException("$id: unknown where"),
                    text = c.optString("text").ifEmpty { null },
                    notices = c.optJSONArray("notices")?.let { n -> (0 until n.length()).map { j -> n.getJSONObject(j).let { LicenceFile(it.getString("title"), it.getString("file")) } } }.orEmpty(),
                    maven = strings(c.optJSONArray("maven")), match = strings(c.optJSONArray("match")),
                    note = c.optString("note").ifEmpty { null },
                )
            }
            return LicenceBook(robot, licences, components)
        }

        private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map(it::getString) }.orEmpty()

        /** "group:artifact" with * standing for any run of characters, against one artifact. */
        fun matches(pattern: String, group: String, artifact: String): Boolean {
            val (g, n) = pattern.split(':').takeIf { it.size == 2 } ?: return false
            fun glob(p: String, s: String) = Regex(p.split('*').joinToString(".*") { Regex.escape(it) }).matches(s)
            return glob(g, group) && glob(n, artifact)
        }

        /**
         * A long text in pieces for a lazy list (LiteRT-LM's notices are 2.2 MB): whole paragraphs together up to about [max]
         * characters, a longer paragraph by lines, a longer line cut. Nothing is reordered, and only the blank line where one
         * piece ends and the next begins is left out (the list shows the gap).
         */
        fun chunks(text: String, max: Int = 4_000): List<String> {
            require(max > 0)
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } }
            for (line in text.replace("\r\n", "\n").split('\n')) {
                var l = line
                // A blank line ends a paragraph: a good place for a piece to end once it is long enough.
                if (l.isBlank() && cur.length >= max / 2) { flush(); continue }
                while (l.length > max) { flush(); out += l.substring(0, max); l = l.substring(max) }
                if (cur.isNotEmpty() && cur.length + 1 + l.length > max) flush()
                if (cur.isNotEmpty()) cur.append('\n')
                cur.append(l)
            }
            flush()
            return out
        }
    }
}

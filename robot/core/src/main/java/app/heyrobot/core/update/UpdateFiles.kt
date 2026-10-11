package app.heyrobot.core.update

import org.json.JSONObject

// Pure. The updater's two small files, kept apart from settings.json (as look.json is) so they never meet its schema:
// updates.json holds your choices in Settings › Updates, update_state.json what the updater last found and did. Both are
// read forward-compatibly: a missing, damaged or newer file gives the defaults for what it doesn't say, unknown keys and a
// known key of the wrong type are ignored. The access token is in neither: it is encrypted in its own file
// (app/update/UpdateToken.kt).

/**
 * Settings › Updates. [repo] null: the repository Settings › About › Licences names (components.json's robot.source);
 * otherwise a fork you chose, "owner/name". [skipped]: the version you said to skip ("Skip this version").
 */
data class UpdateSettings(
    val autoCheck: Boolean = true,
    val wifiDownload: Boolean = true,
    val testBuilds: Boolean = false,
    val installWithoutAsking: Boolean = false,
    val repo: String? = null,
    val skipped: String? = null,
) {
    /** The repository to ask: yours if you set one, else [default]. */
    fun repoOr(default: GitHubRepo?): GitHubRepo? = repo?.let(GitHubRepo::parse) ?: default

    fun toJson(): String = JSONObject()
        .put("v", VERSION)
        .put("autoCheck", autoCheck)
        .put("wifiDownload", wifiDownload)
        .put("testBuilds", testBuilds)
        .put("installWithoutAsking", installWithoutAsking)
        .apply { repo?.let { put("repo", it) } }
        .apply { skipped?.let { put("skipped", it) } }
        .toString()

    companion object {
        const val FILE = "updates.json"
        const val VERSION = 1

        fun fromJson(text: String?): UpdateSettings {
            val o = obj(text) ?: return UpdateSettings()
            val d = UpdateSettings()
            return UpdateSettings(
                autoCheck = bool(o, "autoCheck", d.autoCheck),
                wifiDownload = bool(o, "wifiDownload", d.wifiDownload),
                testBuilds = bool(o, "testBuilds", d.testBuilds),
                installWithoutAsking = bool(o, "installWithoutAsking", d.installWithoutAsking),
                repo = str(o, "repo")?.let(GitHubRepo::parse)?.slug,
                skipped = str(o, "skipped")?.let(RobotVersion::parse)?.text,
            )
        }
    }
}

/** How the last check ended. */
enum class CheckOutcome(val id: String) {
    UP_TO_DATE("upToDate"), AVAILABLE("available"), SKIPPED("skipped"), FAILED("failed");

    companion object {
        fun of(id: String?): CheckOutcome? = entries.firstOrNull { it.id == id }
    }
}

/** A release file as the state keeps it: its id (what a download asks for), name and size. */
data class FileRef(val id: Long, val name: String, val size: Long)

/**
 * The release the last successful check offered, as the Updates page shows it: [notes] already plain text
 * ([ReleaseNotes.plain]), [repo] the repository it came from (a change of repository forgets it).
 */
data class FoundRelease(
    val repo: String,
    val tag: String,
    val version: String,
    val testBuild: Boolean,
    val publishedAt: String?,
    val notes: String,
    val apk: FileRef,
    val sums: FileRef,
) {
    val parsedVersion: RobotVersion? get() = RobotVersion.parse(version)

    companion object {
        fun of(repo: GitHubRepo, r: GitHubRelease, files: ReleaseFiles): FoundRelease? {
            val v = r.version ?: return null
            return FoundRelease(repo.slug, r.tag, v.text, r.testBuild, r.publishedAt, ReleaseNotes.plain(r.body),
                FileRef(files.apk.id, files.apk.name, files.apk.size), FileRef(files.sums.id, files.sums.name, files.sums.size))
        }
    }
}

/** A download that passed every check: the file in app storage, its version and the SHA-256 SHA256SUMS gave for it. */
data class VerifiedDownload(val version: String, val file: String, val sha256: String, val size: Long, val apkId: Long)

/**
 * What the updater last found and did. [notified]: the notice last posted ("0.4.6 available", "0.4.6 ready"), so each is
 * posted once. [firstSeen]: when [found] was first offered (a download waiting for Wi-Fi says so after two days).
 * [updatedTo]: Robot was just updated to that version, to say once. [install]: the last install's outcome.
 * [refusedApk]: a release file that failed its checks, and why ([refusedWhy]).
 */
data class UpdateState(
    val lastCheck: Long = 0,
    val outcome: CheckOutcome? = null,
    val fail: UpdateFail? = null,
    val failHttp: Int = 0,
    val retryAt: Long? = null,
    val found: FoundRelease? = null,
    val firstSeen: Long = 0,
    val downloaded: VerifiedDownload? = null,
    val notified: String? = null,
    val updatedTo: String? = null,
    val install: InstallOutcome? = null,
    val installVersion: String? = null,
    /** A release file that failed a check (its sum, its signer...): not downloaded again by itself, only when you ask. */
    val refusedApk: Long = 0,
    val refusedWhy: UpdateFail? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", VERSION)
        put("lastCheck", lastCheck)
        outcome?.let { put("outcome", it.id) }
        fail?.let { put("fail", it.name) }
        if (failHttp != 0) put("failHttp", failHttp)
        retryAt?.let { put("retryAt", it) }
        found?.let { f ->
            put("found", JSONObject().put("repo", f.repo).put("tag", f.tag).put("version", f.version).put("testBuild", f.testBuild)
                .apply { f.publishedAt?.let { put("publishedAt", it) } }.put("notes", f.notes).put("apk", file(f.apk)).put("sums", file(f.sums)))
        }
        if (firstSeen != 0L) put("firstSeen", firstSeen)
        downloaded?.let { d ->
            put("downloaded", JSONObject().put("version", d.version).put("file", d.file).put("sha256", d.sha256).put("size", d.size).put("apkId", d.apkId))
        }
        notified?.let { put("notified", it) }
        updatedTo?.let { put("updatedTo", it) }
        install?.let { put("install", it.name) }
        installVersion?.let { put("installVersion", it) }
        if (refusedApk > 0) put("refusedApk", refusedApk)
        refusedWhy?.let { put("refusedWhy", it.name) }
    }.toString()

    companion object {
        const val FILE = "update_state.json"
        const val VERSION = 1

        private fun file(f: FileRef) = JSONObject().put("id", f.id).put("name", f.name).put("size", f.size)

        fun fromJson(text: String?): UpdateState {
            val o = obj(text) ?: return UpdateState()
            return UpdateState(
                lastCheck = long(o, "lastCheck")?.coerceAtLeast(0) ?: 0,
                outcome = CheckOutcome.of(str(o, "outcome")),
                fail = str(o, "fail")?.let { n -> UpdateFail.entries.firstOrNull { it.name == n } },
                failHttp = long(o, "failHttp")?.toInt() ?: 0,
                retryAt = long(o, "retryAt"),
                found = (o.opt("found") as? JSONObject)?.let(::found),
                firstSeen = long(o, "firstSeen") ?: 0,
                downloaded = (o.opt("downloaded") as? JSONObject)?.let(::downloaded),
                notified = str(o, "notified"),
                updatedTo = str(o, "updatedTo")?.let(RobotVersion::parse)?.text,
                install = str(o, "install")?.let { n -> InstallOutcome.entries.firstOrNull { it.name == n } },
                installVersion = str(o, "installVersion")?.let(RobotVersion::parse)?.text,
                refusedApk = long(o, "refusedApk")?.takeIf { it > 0 } ?: 0,
                refusedWhy = str(o, "refusedWhy")?.let { n -> UpdateFail.entries.firstOrNull { it.name == n } },
            )
        }

        private fun fileRef(o: JSONObject?): FileRef? {
            o ?: return null
            val id = long(o, "id")?.takeIf { it > 0 } ?: return null
            val name = str(o, "name") ?: return null
            val size = long(o, "size")?.takeIf { it >= 0 } ?: return null
            return FileRef(id, name, size)
        }

        private fun found(o: JSONObject): FoundRelease? {
            val repo = str(o, "repo")?.let(GitHubRepo::parse)?.slug ?: return null
            val tag = str(o, "tag") ?: return null
            val version = str(o, "version")?.let(RobotVersion::parse)?.text ?: return null
            return FoundRelease(repo, tag, version, bool(o, "testBuild", true), str(o, "publishedAt"),
                ReleaseNotes.plain(str(o, "notes")), fileRef(o.opt("apk") as? JSONObject) ?: return null, fileRef(o.opt("sums") as? JSONObject) ?: return null)
        }

        private fun downloaded(o: JSONObject): VerifiedDownload? {
            val version = str(o, "version")?.let(RobotVersion::parse)?.text ?: return null
            val file = str(o, "file")?.takeIf { it == UpdateFilesNames.apk(version) } ?: return null
            val sha = str(o, "sha256")?.takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } } ?: return null
            val size = long(o, "size")?.takeIf { it > 0 } ?: return null
            val apkId = long(o, "apkId")?.takeIf { it > 0 } ?: return null
            return VerifiedDownload(version, file, sha, size, apkId)
        }
    }
}

/** The names of the files in the updates folder (files/updates), from a parsed version only: never a path. */
object UpdateFilesNames {
    const val DIR = "updates"
    const val PART = ".part"

    /** "Robot-0.4.6.apk"; [version] must parse (its characters are ASCII letters, digits, "." and "-"). */
    fun apk(version: String): String {
        val v = requireNotNull(RobotVersion.parse(version)) { "not a version" }
        return ReleaseAssets.apkName(v)
    }

    fun part(version: String): String = apk(version) + PART
}

private fun obj(text: String?): JSONObject? = try { JSONObject(text ?: return null) } catch (_: Exception) { null }

private fun bool(o: JSONObject, key: String, default: Boolean): Boolean = (o.opt(key) as? Boolean) ?: default

private fun str(o: JSONObject, key: String): String? = o.opt(key) as? String

private fun long(o: JSONObject, key: String): Long? = when (val v = o.opt(key)) {
    is Int -> v.toLong()
    is Long -> v
    else -> null
}

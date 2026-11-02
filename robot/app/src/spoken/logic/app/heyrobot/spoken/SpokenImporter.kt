// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import app.heyrobot.core.BrainModel
import app.heyrobot.core.InstallResult
import app.heyrobot.core.InstalledFile
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceFile
import app.heyrobot.core.VoiceMark
import app.heyrobot.core.installVerified
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.io.FileInputStream

/**
 * Models from the computer, in the catalogue's own folders, so the test build doesn't download 4 GB. Only a file the catalogue
 * names is taken, at [SpokenAssumptions.IMPORT]/<bundle>/<file> or /<model's file>, and each is checked against the catalogue's
 * size and SHA-256 as a download is. A file that fails is deleted and never marked.
 */
object SpokenImporter {
    sealed class Item(val src: File) {
        class Voice(src: File, val bundle: VoiceBundle, val file: VoiceFile) : Item(src)
        class Brain(src: File, val model: BrainModel) : Item(src)
    }

    /** What's waiting in [dir]; anything the catalogue doesn't name isn't in the list. */
    fun plan(dir: File): List<Item> {
        val voices = VoiceBundle.entries.flatMap { b -> b.files.map { f -> Item.Voice(File(File(dir, b.id), f.name), b, f) } }
        val models = BrainModel.entries.filter { it.builtIn }.map { m -> Item.Brain(File(dir, m.file), m) }
        return (voices + models).filter { it.src.isFile }
    }

    /** One voice model's file, into [bundleDir] with its line in the folder's mark. True when it's in; the source is gone either way. */
    fun installVoice(src: File, f: VoiceFile, bundleDir: File): Boolean {
        val dest = File(bundleDir, f.name)
        try {
            val r = FileInputStream(src).use { installVerified(it, dest, f.bytes, f.sha256) }
            if (r != InstallResult.OK) return false
            val markFile = File(bundleDir, VoiceMark.FILE)
            val marks = VoiceMark.decode(readOrNull(markFile)).filter { it.name != f.name } + InstalledFile(f.name, f.bytes, dest.lastModified(), f.sha256)
            writeAtomic(markFile, VoiceMark.encode(marks))
            return true
        } finally {
            src.delete()
        }
    }
}

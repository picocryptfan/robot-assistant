// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 1.4 (Robot Files, files-1.4-plan.md §2.3): the backup's STREAM moved into a class files share, and backups must stay
 * byte for byte the same. Pinned here before the move, on 1.1's own writer and reader: the SHA-256 of the files the
 * writer makes from fixed inputs (a whole backup of the fixtures' content with a raw key, streams at the chunk edges, one
 * written in odd pieces, a 20 MiB one), and how the reader classifies every cut, flipped bit, swap, drop and addition of
 * a four-chunk file. ROBOT_STREAM_DUMP=<folder> also writes the files and the table there, to compare two builds with cmp.
 */
class BackupStreamPinTest {
    private val c = BackupFormat.CHUNK
    private val s = BackupFormat.SEALED
    private val h = BackupFormat.HEADER_SIZE
    private val dump: File? = System.getenv("ROBOT_STREAM_DUMP")?.let(::File)?.also { it.mkdirs() }

    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun keep(name: String, b: ByteArray) {
        dump?.resolve(name)?.writeBytes(b)
    }

    private fun data(n: Int, seed: Int = n) = Random(seed).nextBytes(n)

    /** The writer's output for each fixed input, by name. */
    private fun outputs(): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        // The content as it was pinned (1.4's Files content came after the pin: the writer's bytes for this input stay the same).
        out["backup-content"] = BackupKit.encrypt(BackupKit.contentRecords(BackupKit.content() - BackupCategory.FILES))
        for (n in listOf(1, c - 1, c, c + 1, 2 * c, 3 * c + 17, 5 * 1024 * 1024)) out["stream-$n"] = BackupKit.encrypt(data(n))
        // Odd pieces and single bytes, as BackupStreamTest.writesInAnyPieces writes them.
        val d = data(3 * c + 17)
        val pieces = ByteArrayOutputStream()
        BackupWriter(pieces, BackupKit.header(), BackupKit.key()).use { w ->
            var i = 0
            val r = Random(5)
            while (i < d.size) {
                val k = minOf(d.size - i, r.nextInt(1, 20_000))
                if (k == 1) w.write(d[i].toInt()) else w.write(d, i, k)
                i += k
            }
        }
        out["pieces"] = pieces.toByteArray()
        // Another key and salt.
        out["stream-other-key"] = BackupKit.encrypt(data(2 * c + 5, 77), BackupKit.header(salt = ByteArray(16) { 9 }), BackupKit.key(seed = 3))
        return out
    }

    @Test fun theWriterMakesThePinnedBytes() {
        val got = outputs().mapValues { (n, b) -> keep("$n.bin", b); sha(b) }
        assertEquals(PINNED, got.entries.joinToString("\n") { "${it.key} ${it.value}" })
    }

    /** 20 MiB through the writer in 1 MiB writes, hashed as it goes (320 chunks: the counter's higher bytes move). */
    @Test fun aLongStreamMakesThePinnedBytes() {
        val md = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(b: Int) { md.update(b.toByte()) }
            override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len) }
        }
        val dumpOut = dump?.resolve("stream-20mib.bin")?.outputStream()
        val tee = object : OutputStream() {
            override fun write(b: Int) { sink.write(b); dumpOut?.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len); dumpOut?.write(b, off, len) }
            override fun close() { dumpOut?.close() }
        }
        val r = Random(20)
        val buf = ByteArray(1 shl 20)
        BackupWriter(tee, BackupKit.header(), BackupKit.key()).use { w ->
            repeat(20) {
                r.nextBytes(buf)
                w.write(buf)
            }
        }
        assertEquals(PINNED_20MIB, md.digest().joinToString("") { "%02x".format(it) })
    }

    private fun outcome(file: ByteArray): String = try {
        BackupHeader.parse(file)
        val plain = BackupReader(ByteArrayInputStream(file, h, file.size - h), BackupHeader.parse(file), BackupKit.key()).readBytes()
        "ok ${plain.size} ${sha(plain).take(12)}"
    } catch (x: BackupException) {
        x.problem.name
    } catch (t: Throwable) {
        "crash ${t.javaClass.simpleName}"
    }

    private fun chunks(f: ByteArray): List<ByteArray> = (h until f.size step s).map { f.copyOfRange(it, minOf(it + s, f.size)) }

    private fun join(f: ByteArray, parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(f, 0, h)
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun flip(f: ByteArray, at: Int, mask: Int = 1) = f.copyOf().also { it[at] = (it[at].toInt() xor mask).toByte() }

    /** Every case and what the reader made of it, one line each. */
    private fun table(): String {
        val f = BackupKit.encrypt(data(3 * c + c / 2, 4))
        val full = BackupKit.encrypt(data(2 * c, 2))
        val rows = ArrayList<String>()
        rows += "whole ${outcome(f)}"
        rows += "header only ${outcome(f.copyOf(h))}"
        for (k in 0..3) for (inside in listOf(0, 1, 15, 16, 17, 1000, c, s - 1)) {
            val len = h + k * s + inside
            if (len < f.size) rows += "cut $k+$inside ${outcome(f.copyOf(len))}"
        }
        for (cut in listOf(1, 15, 16, 17, 40)) rows += "short by $cut ${outcome(f.copyOf(f.size - cut))}"
        for (at in 0 until h) rows += "header byte $at ${outcome(flip(f, at))}"
        for (k in 0..3) for (at in listOf(0, 100, c - 1, c, s - 1)) {
            val pos = h + k * s + at
            if (pos < f.size) rows += "chunk $k byte $at ${outcome(flip(f, pos, 0x40))}"
        }
        val p = chunks(f)
        rows += "swap 1 2 ${outcome(join(f, listOf(p[0], p[2], p[1], p[3])))}"
        rows += "swap 0 1 ${outcome(join(f, listOf(p[1], p[0], p[2], p[3])))}"
        rows += "repeat 1 ${outcome(join(f, listOf(p[0], p[1], p[1], p[2], p[3])))}"
        rows += "drop 1 ${outcome(join(f, listOf(p[0], p[2], p[3])))}"
        rows += "drop last ${outcome(join(f, p.dropLast(1)))}"
        rows += "first two ${outcome(join(f, listOf(p[0], p[1])))}"
        for (extra in listOf(1, 16, 100, s)) rows += "short last plus $extra ${outcome(f + ByteArray(extra))}"
        rows += "full whole ${outcome(full)}"
        rows += "full plus 1 ${outcome(full + ByteArray(1))}"
        rows += "full plus a chunk ${outcome(full + ByteArray(s))}"
        rows += "full plus its first chunk ${outcome(full + full.copyOfRange(h, h + s))}"
        for (k in 0..2) rows += "full cut after $k ${outcome(full.copyOf(h + k * s))}"
        rows += "wrong key ${try { BackupReader(ByteArrayInputStream(f, h, f.size - h), BackupHeader.parse(f), BackupKit.key(seed = 2)).readBytes(); "ok" } catch (x: BackupException) { x.problem.name }}"
        return rows.joinToString("\n")
    }

    @Test fun theReaderClassifiesEveryCaseAsBefore() {
        val t = table()
        dump?.resolve("outcomes.txt")?.writeText(t + "\n")
        assertEquals(t, PINNED_TABLE, sha(t.toByteArray()))
    }

    @Test fun theReaderReadsEveryPinnedInputBack() {
        for ((n, b) in outputs()) {
            if (n == "backup-content" || n == "stream-other-key") continue
            val len = n.removePrefix("stream-").toIntOrNull() ?: (3 * c + 17)
            val key = BackupKit.key()
            val r: InputStream = BackupReader(ByteArrayInputStream(b, h, b.size - h), BackupHeader.parse(b), key)
            assertEquals(n, len, r.readBytes().size)
        }
        assertEquals(BackupKit.contentRecords(BackupKit.content() - BackupCategory.FILES).toList(), BackupKit.decrypt(outputs().getValue("backup-content")).toList())
    }

    private companion object {
        /**
         * Made by 1.1's writer (f764d4d4) before the move. 1.4 F9 changed only backup-content's input, not the writer: the
         * fixtures' settings.json holds every tool's approval (RobotSettings.toJson), and find_files and read_file are two more
         * (internal: written, always read back as their default). Every stream below is the same bytes as before.
         */
        val PINNED = """
backup-content de5ea2529114d51bea00b75669640e7a9117a4555dc16cb0bba3304683c3e8d6
stream-1 5735a2f3c7e66c08f62acf11eb714629700b308bd26850bb0966d20084b23083
stream-65535 433cc4ff3f8e3014028baa16af4ec09c07c14a2d45b70aca2c0e3b7e414fec8f
stream-65536 4537ca2f42d664368994346ad30c2f132e3b0a3563f8e879dfcc29abbb9808cf
stream-65537 470dab99a37909a89c49d3c2cc5aa7423f0e7bb0e662fccb9b1e7a287fa0b9db
stream-131072 269733c3c2010733d5441920559b93fa48e2de345a64a0b120396ef68ba830a8
stream-196625 9e8f9568db7ea8387fcfa6abbf2961e705daa8a656d35e7f7b9870b231db240b
stream-5242880 f668f532081a3968782f44f8bab8868ccf0c6110460fc65a866cdbbdcc4f5fae
pieces 9e8f9568db7ea8387fcfa6abbf2961e705daa8a656d35e7f7b9870b231db240b
stream-other-key 211c17ad2396dea5d76ae94953f3542fdb830ca478161a22d3a15086ea99d985
""".trim()
        const val PINNED_20MIB = "431fb0575160d36e340059248048c4bba9a41cb5f1ef7c1371fa5370a3bb41c8"
        /** The SHA-256 of [table]'s 120 lines as 1.1's reader classified them (f764d4d4); a failure prints the table. */
        const val PINNED_TABLE = "7e73013fa5f0485c22bdc9310433725067cff799c9e7458c768229a173ce8bb0"
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.link.TransferView
import app.heyrobot.core.link.XferPaths
import app.heyrobot.core.link.XferState
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.OfferFields
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.seal.FakeInboxWrap
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkCardText
import app.heyrobot.core.link.XferSeal
import app.heyrobot.core.link.requests.RequestCaps
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.security.SecureRandom

/** §3.6 step 5 (job L8): what an arrival must be to go in, and what another Robot's word about a file is worth. */
class FilePeerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val inbox = FakeInboxWrap()
    private val pairing = "ab".repeat(16)
    private val pdf = "%PDF-1.7\nthe bytes of a receipt\n".toByteArray()
    private val apk = byteArrayOf(0x50, 0x4B, 3, 4) + "AndroidManifest.xml classes.dex".toByteArray() + ByteArray(40)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun view(plain: ByteArray, id: String = FileId.new(SecureRandom()).hex, kind: OfferKind = OfferKind.RECEIPT, alone: Boolean = false, name: String = "receipt.pdf",
        fields: OfferFields? = null, shaSaid: String = sha(plain), size: Long = plain.size.toLong(), state: XferState = XferState.ARRIVED, out: Boolean = false) =
        TransferView(id, pairing, out, state, FileOffer("cd".repeat(16), name, kind, size, shaSaid, OfferWhy.OFFER, null, fields), 1, 1_790_000_000, 0, alone)

    private inner class Cabinet {
        val dir = tmp.newFolder()
        val store = FileStore(dir, FakeFileKeys(), "voice", space = { Long.MAX_VALUE / 4 })
        val told = ArrayList<Pair<String, Boolean>>()
        val copy = IntakeCopy({ i, d -> store.stage(i, d) }, { store.discard(it) })
        var notNow = false

        fun arrive(v: TransferView, plain: ByteArray) {
            val f = XferPaths.arrived(dir, v.id); f.parentFile!!.mkdirs()
            f.outputStream().use { o -> FileSeal.arrivedWriter(o, FileId.of(v.id)!!, inbox, SecureRandom()).use { it.write(plain) } }
        }

        fun run(vararg views: TransferView): Int = PeerArrivals({ XferPaths.arrived(dir, it) }, inbox, { i, n -> copy.copy(i, n) },
            { store.duplicateOf(it) != null }, { store.discard(it) }, { r, i -> if (notNow) { store.discard(r.staged); false } else { store.commit(r.staged, i); true } })
            .run(views.toList()) { id, ok -> told += id to ok }

        fun entries() = (store.load() as IndexLoad.Loaded).index.entries
    }

    @Test fun anArrivalGoesInAsFromThatRobotWithItsWordsApart() {
        val c = Cabinet()
        val v = view(pdf, fields = OfferFields("Costco", null, "2026-10-01", 8412, "CAD", "2026-11-01"))
        c.arrive(v, pdf)
        assertEquals(1, c.run(v))
        assertEquals(listOf(v.id to true), c.told)
        val e = c.entries().single()
        assertEquals(FileSource.ROBOT, e.from.source)
        assertEquals(pairing, FilePeer.peerOf(e))
        assertEquals("1", e.extra["mode"]); assertEquals("offer", e.extra["why"])
        assertEquals("application/pdf", e.from.type)
        assertEquals(sha(pdf), e.from.sha256)
        assertArrayEquals(pdf, c.store.open(e.id).use { it.readBytes() })
        // Their word is by PEER, in fields nothing else reads.
        assertEquals(FieldBy.PEER, e.fields["peer_total"]?.by)
        assertEquals("84.12", e.fields["peer_total"]?.value)
        for (k in listOf("total", "due", "date", "merchant", "currency")) assertNull(k, e.fields[k])
        val read = Filer.valuesOf(e.fields)
        assertNull(read.total); assertNull(read.due); assertNull(read.merchant)
        // And they stay through the index and through Robot's own reading.
        val again = (FileIndexCodec.decode(FileIndexCodec.encode(FileIndex(listOf(e)))) as IndexParse.Ok).index.entries.single()
        assertEquals(pairing, FilePeer.peerOf(again))
        assertEquals(FieldBy.PEER, again.fields["peer_total"]?.by)
    }

    @Test fun robotsOwnReadingIsWhatCountsAndAMismatchIsShown() {
        val e = FileEntry(FileId.new(SecureRandom()), "a.pdf", FileKind.RECEIPT, FileState.INBOX, Provenance(FileSource.ROBOT, 1, 1),
            fields = FilePeer.fields(OfferFields("Costco", null, null, 8412, "CAD")))
        assertEquals(listOf(PeerSays("merchant", "Costco", null), PeerSays("total", "84.12", null)), FilePeer.says(e))
        val read = e.copy(fields = e.fields + mapOf("total" to FileField("48.12", FieldBy.READ), "currency" to FileField("CAD", FieldBy.READ), "merchant" to FileField("Costco", FieldBy.READ)))
        val says = FilePeer.says(read)
        assertFalse(says.first { it.key == "merchant" }.differs)
        assertEquals(PeerSays("total", "84.12", "48.12", true), says.first { it.key == "total" })
        assertTrue(says.first { it.key == "total" }.differs)
        val same = e.copy(fields = e.fields + mapOf("total" to FileField("84.12", FieldBy.READ), "currency" to FileField("CAD", FieldBy.READ)))
        assertFalse(FilePeer.says(same).first { it.key == "total" }.differs)
        // The same number in another currency isn't the same total.
        val usd = e.copy(fields = e.fields + mapOf("total" to FileField("84.12", FieldBy.READ), "currency" to FileField("USD", FieldBy.READ)))
        assertTrue(FilePeer.says(usd).first { it.key == "total" }.differs)
    }

    @Test fun theBytesMustBeWhatTheOfferNamedOrNothingGoesIn() {
        val c = Cabinet()
        val wrongHash = view(pdf, shaSaid = sha(apk)); c.arrive(wrongHash, pdf)
        val wrongSize = view(pdf, size = pdf.size + 1L); c.arrive(wrongSize, pdf)
        assertEquals(0, c.run(wrongHash, wrongSize))
        assertEquals(listOf(wrongHash.id to false, wrongSize.id to false), c.told)
        assertEquals(0, c.store.usage().files)
    }

    @Test fun bytesThatCanInstallOrRunGoInOnlyWhenTheOfferSaidSoAndYouSaidYes() {
        assertEquals(FileGroup.DANGEROUS, FileSniff.sniff(apk, apk.size.toLong()).group)
        val c = Cabinet()
        val calledAReceipt = view(apk); c.arrive(calledAReceipt, apk)
        val onItsOwn = view(apk, kind = OfferKind.DANGEROUS, alone = true); c.arrive(onItsOwn, apk)
        assertEquals(0, c.run(calledAReceipt, onItsOwn))
        assertEquals(listOf(calledAReceipt.id to false, onItsOwn.id to false), c.told)
        val said = view(apk, kind = OfferKind.DANGEROUS); c.arrive(said, apk)
        assertEquals(1, c.run(said))
        val e = c.entries().single()
        assertEquals(FileKind.DANGEROUS, e.kind)
        assertEquals(FilingStep.HELD, e.filing?.step)
    }

    @Test fun whatCantBeDoneNowStaysAndWhatCantEverBeIsToldAndOnlyArrivalsCount() {
        val c = Cabinet()
        val v = view(pdf); c.arrive(v, pdf)
        c.notNow = true
        assertEquals(0, c.run(v))
        assertEquals(emptyList<Pair<String, Boolean>>(), c.told)
        assertTrue(XferPaths.arrived(c.dir, v.id).isFile)
        c.notNow = false
        // Its file gone, or damaged: told, never retried for ever.
        val gone = view(pdf)
        val cut = view(pdf); c.arrive(cut, pdf)
        val f = XferPaths.arrived(c.dir, cut.id); f.writeBytes(f.readBytes().copyOf(f.length().toInt() - 5))
        assertEquals(0, c.run(gone, cut))
        assertEquals(listOf(gone.id to false, cut.id to false), c.told)
        c.told.clear()
        // Not yet arrived, or going the other way: not read at all.
        assertEquals(0, c.run(view(pdf, state = XferState.RECEIVING), view(pdf, out = true)))
        assertEquals(emptyList<Pair<String, Boolean>>(), c.told)
        // A file you have already: settled, no second copy, and a crash between the commit and the telling ends the same way.
        assertEquals(1, c.run(v))
        c.told.clear()
        c.arrive(v, pdf)
        assertEquals(0, c.run(v))
        assertEquals(listOf(v.id to true), c.told)
        assertEquals(1, c.entries().size)
    }

    @Test fun anArrivalWaitingIsNotSealedUnderFilesKeySoACabinetWithOnlyThatCanStillBeMade() {
        // F5's rule (nothing sealed under a key before replacing it): a format 2 file's key is robot_inbox_v1, so it doesn't count.
        val dir = tmp.newFolder()
        XferPaths.arrived(dir).mkdirs()
        XferPaths.arrived(dir, "ab".repeat(16)).writeBytes(ByteArray(4))
        val store = FileStore(dir, FakeFileKeys(), "voice", space = { Long.MAX_VALUE / 4 })
        assertNotNull(store.stage("a few bytes".byteInputStream(), 11))
        assertTrue(store.load() is IndexLoad.Loaded)
        assertTrue(XferPaths.arrived(dir, "ab".repeat(16)).isFile)
    }

    @Test fun aPhotosOrAVideosDateTakenStaysHome() {
        fun entry(kind: FileKind, vararg f: Pair<String, String>) = FileEntry(FileId.new(SecureRandom()), "a.jpg", kind, FileState.FILED, Provenance(FileSource.SHARE, 1, 1),
            fields = f.associate { (k, v) -> k to FileField(v, FieldBy.READ) })
        for (kind in listOf(FileKind.PHOTO, FileKind.VIDEO)) {
            assertNull(FilePeer.offerFields(entry(kind, "date" to "2026-10-01")))
            FilePeer.offerFields(entry(kind, "date" to "2026-10-01", "title" to "Beach"))!!.let { assertNull(it.date); assertEquals("Beach", it.title) }
        }
        assertEquals("2026-10-01", FilePeer.offerFields(entry(FileKind.RECEIPT, "date" to "2026-10-01"))!!.date)
    }

    @Test fun whatGoesOutIsOnlyWhatRobotReadOrYouSetAndANameThatFits() {
        val e = FileEntry(FileId.new(SecureRandom()), "a.pdf", FileKind.RECEIPT, FileState.FILED, Provenance(FileSource.SHARE, 1, 1), fields = mapOf(
            "merchant" to FileField("Costco", FieldBy.READ), "total" to FileField("84.12", FieldBy.YOU), "currency" to FileField("CAD", FieldBy.READ),
            "date" to FileField("not a date", FieldBy.READ), "peer_due" to FileField("2026-11-01", FieldBy.PEER), "account" to FileField("12345", FieldBy.READ)))
        val o = FilePeer.offerFields(e)!!
        assertEquals("Costco", o.merchant); assertEquals(8412L, o.total); assertEquals("CAD", o.currency); assertNull(o.date); assertNull(o.due)
        // Another Robot's word never goes on as yours, and a total without its currency doesn't go.
        val theirs = e.copy(fields = mapOf("total" to FileField("84.12", FieldBy.PEER), "currency" to FileField("CAD", FieldBy.PEER)))
        assertNull(FilePeer.offerFields(theirs))
        assertNull(FilePeer.offerFields(e.copy(fields = mapOf("total" to FileField("84.12", FieldBy.READ)))))
        assertEquals(OfferKind.RECEIPT, FilePeer.kindOf(e, FileType.PDF))
        assertEquals(OfferKind.DANGEROUS, FilePeer.kindOf(e, FileType.entries.first { it.group == FileGroup.DANGEROUS }))
        assertEquals(OfferKind.OTHER, FilePeer.kindOf(e.copy(kind = FileKind.UNKNOWN), FileType.PDF))
        val long = "x".repeat(200) + ".pdf"
        assertEquals(80, FilePeer.offerName(long)!!.length)
        assertTrue(FilePeer.offerName(long)!!.endsWith(".pdf"))
        assertEquals("a.pdf", FilePeer.offerName("a.pdf"))
        assertNull(FilePeer.offerName("a/b.pdf"))
    }

    // Going out -------------------------------------------------------------------------------------------------------------

    private inner class Out(val isLocked: () -> Boolean = { false }, val accept: Boolean = true) {
        val dir = tmp.newFolder()
        var posted: TransferView? = null
        var key: ByteArray? = null
        var keyHeld: ByteArray? = null
        val out = PeerOut({ File(dir, "$it.xfr") }, { v, k -> posted = v; keyHeld = k; key = k.copyOf(); accept }, isLocked)
    }

    private fun mine(size: Long = 10) = FileEntry(FileId.new(SecureRandom()), "a.pdf", FileKind.RECEIPT, FileState.FILED, Provenance(FileSource.SHARE, 1, size), fields = mapOf(
        "merchant" to FileField("Costco", FieldBy.READ), "total" to FileField("84.12", FieldBy.READ), "currency" to FileField("CAD", FieldBy.READ),
        "peer_due" to FileField("2026-12-01", FieldBy.PEER)))

    private val plainType = FileType.entries.first { it.group != FileGroup.DANGEROUS }

    @Test fun whatGoesOutIsSealedWithItsHashAndOnlyRobotsOwnFields() {
        val o = Out()
        val plain = ByteArray(200_000) { it.toByte() }
        assertEquals(PeerSent.OFFERED, o.out.send("p", mine(), plainType, "a.pdf", OfferWhy.REQUEST, "b".repeat(32)) { plain.inputStream() })
        val v = o.posted!!
        assertEquals(LinkBytes.hex(MessageDigest.getInstance("SHA-256").digest(plain)), v.offer.sha256)
        assertEquals(plain.size.toLong(), v.offer.size)
        assertEquals(OfferWhy.REQUEST, v.offer.why)
        assertEquals("b".repeat(32), v.offer.re)
        assertEquals("Costco", v.offer.fields!!.merchant)
        // Another Robot's word is never passed on as this one's.
        assertNull(v.offer.fields!!.due)
        val copy = File(o.dir, v.id + ".xfr")
        assertEquals(XferSeal.sealedSize(plain.size.toLong()), copy.length())
        assertEquals("only the copy", 1, o.dir.listFiles()!!.size)
        // The plain bytes aren't on disk; the key that opens them was wiped here after :link had it.
        val raw = copy.readBytes()
        assertFalse(raw.toList().windowed(16, 4096).any { w -> w == plain.take(16) })
        assertTrue(o.keyHeld!!.all { it == 0.toByte() })
        assertFalse(o.key!!.all { it == 0.toByte() })
    }

    @Test fun aPhoneThatLocksWhileTheCopyIsMadeSendsNothing() {
        var calls = 0
        val o = Out({ calls++ > 0 })
        assertEquals(PeerSent.LOCKED, o.out.send("p", mine(), plainType, "a.pdf", OfferWhy.OFFER, null) { ByteArray(10).inputStream() })
        assertNull(o.posted)
        assertEquals(0, o.dir.listFiles()!!.size)
    }

    @Test fun aFileOverTheLimitNeverStarts() {
        val o = Out()
        var read = false
        assertEquals(PeerSent.TOO_BIG, o.out.send("p", mine(RequestCaps.FILE_MAX_BYTES + 1), plainType, "a.pdf", OfferWhy.OFFER, null) { read = true; ByteArray(10).inputStream() })
        assertFalse(read)
        assertNull(o.posted)
    }

    @Test fun noCopyOrARefusedOfferLeavesNothingBehind() {
        for ((plain, want) in listOf<Pair<() -> java.io.InputStream?, PeerSent>>({ null } to PeerSent.NO_COPY, { ByteArray(0).inputStream() } to PeerSent.NO_COPY,
            { throw java.io.IOException() } to PeerSent.FAILED)) {
            val o = Out()
            assertEquals(want, o.out.send("p", mine(), plainType, "a.pdf", OfferWhy.OFFER, null, plain))
            assertNull(o.posted)
            assertEquals(0, o.dir.listFiles()!!.size)
        }
        val o = Out(accept = false)
        assertEquals(PeerSent.FAILED, o.out.send("p", mine(), plainType, "a.pdf", OfferWhy.OFFER, null) { ByteArray(10).inputStream() })
        assertEquals("a refused offer leaves no copy", 0, o.dir.listFiles()!!.size)
    }

    // What you read ---------------------------------------------------------------------------------------------------------

    @Test fun theWordsOfAMismatchInBothLanguages() {
        val en = Langs(Lang.EN, Lang.EN, Lang.EN)
        val fr = Langs(Lang.FR, Lang.FR, Lang.FR)
        val says = LinkStrings.robotSaysReads("name" to "Sam", "said" to "84.12 $", "reads" to "48.12 $")
        assertEquals("Sam's Robot says 84.12 $; Robot reads 48.12 $.", says.render(en))
        assertEquals("Le Robot de Sam dit 84.12 $\u202f; Robot lit 48.12 $.", says.render(fr))
        assertEquals("From Sam's Robot", LinkStrings.fromRobot("name" to "Sam").render(en))
        assertEquals("Du Robot de Sam", LinkStrings.fromRobot("name" to "Sam").render(fr))
    }

    @Test fun whereAFileStandsInBothLanguages() {
        val en = Langs(Lang.EN, Lang.EN, Lang.EN)
        val fr = Langs(Lang.FR, Lang.FR, Lang.FR)
        val offer = FileOffer("a".repeat(32), "a.pdf", OfferKind.entries.first(), 1, "a".repeat(64), OfferWhy.OFFER)
        fun line(out: Boolean, s: XferState) = LinkCardText.transfer(TransferView("b".repeat(32), "p", out, s, offer, 0, 0, 0, false))
        for (out in listOf(true, false)) for (s in XferState.entries) { assertTrue(line(out, s).render(en).isNotBlank()); assertTrue(line(out, s).render(fr).isNotBlank()) }
        assertEquals("Sent", line(true, XferState.SENT).render(en))
        assertEquals("Envoy\u00e9", line(true, XferState.SENT).render(fr))
        assertEquals("Not received, nothing kept", line(false, XferState.FAILED).render(en))
        assertEquals("Pas re\u00e7u, rien de conserv\u00e9", line(false, XferState.FAILED).render(fr))
        assertEquals("Received, in Robot Files", line(false, XferState.FILED).render(en))
    }

    @Test fun aCamerasDatedNameDoesNotGoWithAPhotoOrAVideo() {
        fun sent(kind: FileKind, name: String, named: Boolean = false): String {
            val o = Out()
            val e = FileEntry(FileId.new(SecureRandom()), name, kind, FileState.FILED, Provenance(FileSource.SHARE, 1, 10), named = named)
            assertEquals(PeerSent.OFFERED, o.out.send("p", e, plainType, name, OfferWhy.REQUEST, null) { ByteArray(10).inputStream() })
            return o.posted!!.offer.name
        }
        assertEquals("photo.jpg", sent(FileKind.PHOTO, "PXL_20261001_123456789.jpg"))
        assertEquals("photo.jpg", sent(FileKind.PHOTO, "IMG-20261001-WA0001.jpg"))
        assertEquals("photo.jpg", sent(FileKind.PHOTO, "1791234567890.jpg"))
        assertEquals("video.mp4", sent(FileKind.VIDEO, "2026-10-01 12.30.00.mp4"))
        assertEquals("Beach.jpg", sent(FileKind.PHOTO, "Beach.jpg"))
        assertEquals("IMG_1234.jpg", sent(FileKind.PHOTO, "IMG_1234.jpg"))
        assertEquals("Beach 20261001.jpg", sent(FileKind.PHOTO, "Beach 20261001.jpg", named = true))
        assertEquals("2026-10-01 Costco.pdf", sent(FileKind.RECEIPT, "2026-10-01 Costco.pdf"))
    }

    /** F87, review-fixes-check item 1: what the file is decides, not what Robot has filed it as so far. */
    @Test fun aCamerasDatedNameDoesNotGoWithAPictureWhateverItsKindInInbox() {
        fun sent(kind: FileKind, type: FileType, name: String, state: FileState = FileState.INBOX, named: Boolean = false, fields: Map<String, FileField> = emptyMap()): String {
            val o = Out()
            val e = FileEntry(FileId.new(SecureRandom()), name, kind, state, Provenance(FileSource.SHARE, 1, 10), named = named, fields = fields)
            assertEquals(PeerSent.OFFERED, o.out.send("p", e, type, name, OfferWhy.OFFER, null) { ByteArray(10).inputStream() })
            return o.posted!!.offer.name
        }
        fun read(v: String) = FileField(v, FieldBy.READ)
        // Waiting in Inbox with no model: still UNKNOWN.
        assertEquals("photo.jpg", sent(FileKind.UNKNOWN, FileType.JPEG, "PXL_20261001_123456789.jpg"))
        assertEquals("photo.heic", sent(FileKind.UNKNOWN, FileType.HEIC, "IMG_20261001_1234.heic"))
        assertEquals("photo.gif", sent(FileKind.UNKNOWN, FileType.GIF, "1791234567890.gif"))
        assertEquals("video.mp4", sent(FileKind.UNKNOWN, FileType.MP4, "VID_20261001_123456.mp4"))
        // Filed with no name to build: the camera's stays, now under another kind.
        assertEquals("photo.jpg", sent(FileKind.RECEIPT, FileType.JPEG, "PXL_20261001_123456789.jpg", FileState.FILED))
        assertEquals("photo.jpg", sent(FileKind.DOCUMENT, FileType.JPEG, "20261001_123456.jpg", FileState.FILED))
        // The same day on the receipt doesn't make the camera's name Robot's.
        assertEquals("photo.jpg", sent(FileKind.RECEIPT, FileType.JPEG, "PXL_20261001_123456789.jpg", FileState.FILED, fields = mapOf("date" to read("2026-10-01"))))
        // A name Robot built from what it read holds the file's own date, which the offer carries anyway.
        assertEquals("2026-10-01 Costco 84.12 CAD.jpg", sent(FileKind.RECEIPT, FileType.JPEG, "2026-10-01 Costco 84.12 CAD.jpg", FileState.FILED, fields = mapOf("date" to read("2026-10-01"))))
        assertEquals("Lease 2026-07-01.jpg", sent(FileKind.DOCUMENT, FileType.JPEG, "Lease 2026-07-01.jpg", FileState.FILED, fields = mapOf("date" to read("2026-07-01"))))
        assertEquals("2026-09 Hydro due 2026-10-17.png", sent(FileKind.BILL, FileType.PNG, "2026-09 Hydro due 2026-10-17.png", FileState.FILED, fields = mapOf("due" to read("2026-10-17"))))
        // A date that came from another Robot isn't Robot's reading, and a photo's date never is the file's own.
        assertEquals("photo.jpg", sent(FileKind.RECEIPT, FileType.JPEG, "2026-10-01 Costco.jpg", FileState.FILED, fields = mapOf("date" to FileField("2026-10-01", FieldBy.PEER))))
        assertEquals("photo.jpg", sent(FileKind.PHOTO, FileType.JPEG, "2026-10-01 Beach.jpg", FileState.FILED, fields = mapOf("date" to read("2026-10-01"))))
        // Your name stays, a picture without a date stays, and other types of file aren't pictures.
        assertEquals("Beach 20261001.jpg", sent(FileKind.UNKNOWN, FileType.JPEG, "Beach 20261001.jpg", named = true))
        assertEquals("Beach.jpg", sent(FileKind.UNKNOWN, FileType.JPEG, "Beach.jpg"))
        assertEquals("Scan_20261001.pdf", sent(FileKind.UNKNOWN, FileType.PDF, "Scan_20261001.pdf"))
    }
}

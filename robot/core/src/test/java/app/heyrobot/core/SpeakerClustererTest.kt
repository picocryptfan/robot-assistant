// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Speaker labels from synthetic embeddings (docs/conversation-mode.md 3.2, 3.3). */
class SpeakerClustererTest {
    /** A unit vector in 8 dimensions at [deg] degrees from axis 0 towards axis [axis]. */
    private fun v(axis: Int, deg: Double = 90.0): FloatArray = FloatArray(8).also {
        val r = Math.toRadians(deg)
        it[0] = cos(r).toFloat()
        it[axis] += sin(r).toFloat()
    }
    private val a = v(1, 0.0)          // speaker A: axis 0
    private val b = v(2)               // speaker B: axis 2, cosine 0 with A
    private val c = v(3)               // speaker C: axis 3

    private fun SpeakerClusterer.say(e: FloatArray?, at: Long, ms: Int = 2_000) = assign(e, ms, at, at + ms)

    @Test fun joinNewAndProvisional() {
        val k = SpeakerClusterer()
        assertEquals(SpeakerTurn("S1", SpeakerKind.NEW, 0f), k.say(a, 0))
        val near = k.say(v(1, 20.0), 3_000)
        assertEquals("S1" to SpeakerKind.JOINED, near.label to near.kind)
        assertTrue(near.similarity > 0.9f)
        assertEquals("S2" to SpeakerKind.NEW, k.say(b, 6_000).let { it.label to it.kind })
        // Between the thresholds (cosine about 0.38 to S1): labelled S1, but S1 doesn't learn from it.
        val before = k.centroidOf("S1")!!
        val mid = k.say(v(4, 68.0), 9_000)
        assertEquals("S1" to SpeakerKind.PROVISIONAL, mid.label to mid.kind)
        assertTrue(mid.similarity in 0.30f..0.45f)
        assertArrayEquals(before, k.centroidOf("S1")!!, 0f)
        assertEquals(listOf("S1", "S2"), k.labels)
    }

    @Test fun theInterpreterTellsTwoPeopleApartAtMost() {
        val k = SpeakerClusterer(maxSpeakers = ConversationSettings.INTERPRETER_SPEAKERS)
        k.say(a, 0)
        k.say(b, 3_000)
        // A third voice goes to the nearest of the two.
        val third = k.say(v(3, 80.0), 6_000)
        assertEquals("S1" to SpeakerKind.CAPPED, third.label to third.kind)
        assertEquals(listOf("S1", "S2"), k.labels)
    }

    @Test fun shortSegmentsInheritOrAreUnknown() {
        val k = SpeakerClusterer()
        k.say(a, 0)
        // "Oui" 0.5 s after S1 stopped, too short to compare: S1's.
        assertEquals(SpeakerTurn("S1", SpeakerKind.INHERITED), k.assign(b, 600, 2_500, 3_100))
        // Another one 3 s later: "?".
        assertEquals(SpeakerTurn(SpeakerClusterer.UNKNOWN, SpeakerKind.UNKNOWN), k.assign(b, 600, 6_100, 6_700))
        // ... and one right after a "?" stays "?". No embedding at all counts as too short.
        assertEquals(SpeakerClusterer.UNKNOWN, k.assign(null, 2_000, 6_800, 8_800).label)
        assertEquals(listOf("S1"), k.labels)
    }

    @Test fun meIsSeededAndFixed() {
        val k = SpeakerClusterer(maxSpeakers = 2)
        k.seedMe(c)
        assertTrue(k.seeded)
        assertEquals(SpeakerClusterer.ME to SpeakerKind.JOINED, k.say(v(3, 75.0).also { it[3] = 1f }, 0).let { it.label to it.kind })
        // Its centroid never moves, and it counts towards the cap.
        assertArrayEquals(SpeakerClusterer.unit(c), k.centroidOf(SpeakerClusterer.ME)!!, 0f)
        assertEquals("S1", k.say(a, 3_000).label)
        assertEquals(SpeakerKind.CAPPED, k.say(b, 6_000).kind)
        // "Me" can't be merged into someone else.
        assertFalse(k.merge(SpeakerClusterer.ME, "S1"))
    }

    @Test fun mergeTeachAndProposals() {
        val k = SpeakerClusterer()
        k.say(a, 0)
        k.say(b, 3_000)
        k.say(v(2, 80.0), 6_000)
        // Nothing is proposed between two different voices at the join threshold; a looser one finds the pair.
        assertEquals(emptyList<Pair<String, String>>(), k.proposals())
        val c2 = SpeakerClusterer()
        c2.say(a, 0)
        c2.say(v(5, 74.0), 3_000)  // cosine 0.28 with A: a new speaker
        assertEquals(listOf("S1", "S2"), c2.labels)
        assertEquals(listOf("S2" to "S1"), c2.proposals(threshold = 0.25f))
        // Proposals change nothing.
        assertEquals(listOf("S1", "S2"), c2.labels)
        // "S2 is S1": later segments like S2 go to S1.
        assertTrue(c2.merge("S2", "S1"))
        assertEquals(listOf("S1"), c2.labels)
        assertEquals("S1", c2.say(v(5, 74.0), 6_000).label)
        assertFalse(c2.merge("S2", "S1"))
        // "This line was S2": its voice joins S2.
        val before = k.centroidOf("S2")!!
        assertTrue(k.teach("S2", v(6, 85.0)))
        assertFalse(before.contentEquals(k.centroidOf("S2")!!))
        assertFalse(k.teach("S9", a))
        k.clear()
        assertEquals(emptyList<String>(), k.labels)
    }

    @Test fun aResumedSessionStartsNewLabels() {
        val k = SpeakerClusterer(mark = "′")
        assertEquals("S1′", k.say(a, 0).label)
        assertEquals("S2′", k.say(b, 3_000).label)
    }

    @Test fun finishedLinesChangeOnlyThroughYourEvents() {
        // The clusterer hands out labels; the transcript's lines change only when you merge, rename, split or move.
        val k = SpeakerClusterer()
        val fold = TranscriptFold()
        listOf(a, b, a).forEachIndexed { i, e ->
            val t = k.say(e, i * 3_000L)
            fold.apply(TranscriptEvent.Line(i, i * 3.0, i * 3.0 + 2, t.label, "fr", "ligne $i"))
        }
        k.merge("S2", "S1")
        assertEquals(listOf("S1", "S2", "S1"), fold.result().lines.map { it.speaker })
        fold.apply(TranscriptEvent.Merge("S2", "S1"))
        assertEquals(listOf("S1", "S1", "S1"), fold.result().lines.map { it.speaker })
        fold.apply(TranscriptEvent.Rename("S1", "Marie"))
        fold.apply(TranscriptEvent.Split(2, 5, 3, "S3"))
        val t = fold.result()
        assertEquals(listOf("ligne", "2"), t.lines.takeLast(2).map { it.text })
        assertEquals(listOf("S1", "S3"), t.lines.takeLast(2).map { it.speaker })
        assertEquals("Marie", t.nameOf("S1"))
    }
}

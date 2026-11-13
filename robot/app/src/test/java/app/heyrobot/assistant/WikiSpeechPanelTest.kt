// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** L4 (review-wikipedia-p4): the copy's answer in a car or a headset is said by sentence (core WikiSpeech). */
class WikiSpeechPanelTest {
    private val wiki = File("src/main/java/app/heyrobot/assistant/PanelWiki.kt").readText()

    @Test fun aCarOrHeadsetHearsEachBackedSentence() {
        assertTrue("only in a car or a headset", wiki.contains("val bySentence = spoken && PanelHandsFree.active"))
        assertTrue("on the main thread, as ReplyVoice wants", wiki.contains("withContext(Dispatchers.Main) { BrainOneShots.run("))
        assertTrue("each backed sentence through ReplyVoice", wiki.contains("talk?.feed(soFar)?.forEach { s -> voice.feed(replyId, \"\$s \", speak()).forEach(say) }"))
        // M1 (review-wikipedia-l4): the bubble and the voice take the same end (WikiSpeech.end).
        assertTrue("the end shows what was said", wiki.contains("val end = talk?.end(heard, r.reply.spoken)") && wiki.contains("replies.setText(replyId, end?.shown ?: r.reply.shown)"))
        assertTrue("the end says only the rest", wiki.contains("voice.feed(replyId, end?.rest ?: r.reply.spoken, speak()).forEach(say)"))
    }

    /**
     * L4 (review-wikipedia-l4): ANSWER_MS and a :brain cancel end as Stopped: what was said stays and its month line is said
     * before the turn ends (Stop itself: stopGenerating). A failed one-shot still stops the voice, with its own code (L1).
     */
    @Test fun aStoppedAnswerEndsWhatWasSaid() {
        val stopped = wiki.substringAfter("WikiTurn.Result.Stopped ->").substringBefore("is WikiTurn.Result.Answered")
        assertTrue(stopped, stopped.contains("talk?.end(heard, null)") && stopped.indexOf("voice.say(replyId, rest") in 0 until stopped.indexOf("finish(replyId, DoneReason.CANCELLED)"))
        assertTrue(wiki.contains("voice.mute()") && wiki.contains("VoiceHub.stopVoice(TtsStop.COPY)"))
    }
}

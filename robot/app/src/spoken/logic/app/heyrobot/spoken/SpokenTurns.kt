// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import app.heyrobot.assistant.PanelState
import app.heyrobot.assistant.Phase
import app.heyrobot.core.TurnRole
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watches the panel's state and makes the turn lines of the protocol: one when a reply has settled or a card is showing, and
 * another if that turn later changes (a card answered, more text), never two the same. A reply being written gives none.
 */
class SpokenTurns(private val now: () -> Long = System::currentTimeMillis) {
    private val said = HashMap<String, String>()
    private val heardAt = HashMap<String, Long>()

    fun observe(st: PanelState, router: String): List<JSONObject> {
        val turns = st.turns
        for (t in turns) if (t.role == TurnRole.USER) heardAt.getOrPut(t.id) { now() }
        val i = turns.indexOfLast { it.role == TurnRole.ASSISTANT }
        if (i < 0) return emptyList()
        val a = turns[i]
        val cards = SpokenCards.showing(st)
        val waiting = cards.isNotEmpty()
        val writing = a.streaming || st.phase == Phase.THINKING || st.phase == Phase.ANSWERING
        if (!waiting && (writing || (a.text.isBlank() && a.actions.isEmpty()))) return emptyList()
        val tools = a.actions.mapNotNull { it.tool?.id }.distinct()
        val user = turns.subList(0, i).lastOrNull { it.role == TurnRole.USER }
        val mark = listOf(a.text, cards, tools).toString()
        if (said[a.id] == mark) return emptyList()
        said[a.id] = mark
        val times = JSONObject()
        user?.let { u -> heardAt[u.id]?.let { times.put("replyMs", now() - it) } }
        return listOf(JSONObject().put("type", "turn").put("heard", user?.text.orEmpty()).put("router", router).put("reply", a.text)
            .put("cards", JSONArray(cards.map { JSONObject().put("kind", it) })).put("tools", JSONArray(tools)).put("times", times))
    }

    fun clear() {
        said.clear()
        heardAt.clear()
    }
}

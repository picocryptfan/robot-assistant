// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

// Pure. A Phrase across processes: the catalog id and its arguments as JSON, rendered by the receiver in
// its own languages, so words crossing from :voice to the main app (or from :brain) aren't fixed in the sender's
// language. Each entry also carries the sender's English: a receiver that doesn't know the id (an older or newer Robot
// on the other side of an update) shows that.

object PhraseWire {
    fun toJson(p: Phrase): JSONObject = when (p) {
        is Phrase.M -> JSONObject().put("id", p.msg.id).put("en", p.en()).apply {
            if (p.args.isNotEmpty()) put("args", JSONObject().apply { for ((k, v) in p.args) put(k, arg(v)) })
        }
        is Phrase.Plain -> JSONObject().put("plain", p.s)
        is Phrase.Join -> JSONObject().put("join", JSONArray().apply { p.parts.forEach { put(toJson(it)) } }).put("sep", p.sep)
        is Phrase.ListOf -> JSONObject().put("list", JSONArray().apply { p.items.forEach { put(toJson(it)) } }).put("kind", p.kind.name)
    }

    /** [o] as a Phrase; null when it isn't one. [known] finds a catalog entry by id ([Catalogs.msg] by default). */
    fun fromJson(o: JSONObject?, known: (String) -> Msg? = Catalogs::msg): Phrase? {
        o ?: return null
        return when {
            o.has("id") -> {
                val msg = known(o.optString("id")) ?: return Phrase.Plain(o.optString("en"))
                val a = o.optJSONObject("args")
                val args = a?.keys()?.asSequence()?.mapNotNull { k -> arg(a.optJSONObject(k), known)?.let { k to it } }?.toMap().orEmpty()
                Phrase.M(msg, args)
            }
            o.has("plain") -> Phrase.Plain(o.optString("plain"))
            o.has("join") -> Phrase.Join(list(o.optJSONArray("join"), known), o.optString("sep", " "))
            o.has("list") -> Phrase.ListOf(list(o.optJSONArray("list"), known), ListKind.entries.firstOrNull { it.name == o.optString("kind") } ?: ListKind.AND)
            else -> null
        }
    }

    fun encode(p: Phrase): String = toJson(p).toString()

    fun decode(s: String?): Phrase? = try { fromJson(JSONObject(s ?: return null)) } catch (_: Exception) { null }

    private fun list(a: JSONArray?, known: (String) -> Msg?): List<Phrase> = (0 until (a?.length() ?: 0)).mapNotNull { fromJson(a?.optJSONObject(it), known) }

    private fun arg(a: Arg): JSONObject = when (a) {
        is Arg.Plain -> JSONObject().put("t", "plain").put("v", a.s)
        is Arg.Count -> JSONObject().put("t", "count").put("v", a.n)
        is Arg.Num -> JSONObject().put("t", "num").put("v", a.x).put("d", a.decimals)
        is Arg.Bytes -> JSONObject().put("t", "bytes").put("v", a.n)
        is Arg.Seconds -> JSONObject().put("t", "seconds").put("v", a.s)
        is Arg.Clock -> JSONObject().put("t", "clock").put("v", a.t.toString())
        is Arg.Day -> JSONObject().put("t", "day").put("v", a.d.toString())
        is Arg.Money -> JSONObject().put("t", "money").put("v", a.cents).apply { a.currency?.let { put("c", it) } }
        is Arg.Date -> JSONObject().put("t", "date").put("v", a.d.toString())
        is Arg.Month -> JSONObject().put("t", "month").put("y", a.year).put("v", a.month)
        is Arg.Sub -> JSONObject().put("t", "sub").put("v", toJson(a.p))
        is Arg.Ui -> JSONObject().put("t", "ui").put("v", a.label.id).put("en", a.label.render(Lang.EN))
        is Arg.Android -> JSONObject().put("t", "android").put("v", a.path.name)
    }

    private fun arg(o: JSONObject?, known: (String) -> Msg?): Arg? {
        o ?: return null
        return try {
            when (o.optString("t")) {
                "plain" -> Arg.Plain(o.optString("v"))
                "count" -> Arg.Count(o.getLong("v"))
                "num" -> Arg.Num(o.getDouble("v"), o.optInt("d", 1))
                "bytes" -> Arg.Bytes(o.getLong("v"))
                "seconds" -> Arg.Seconds(o.getLong("v"))
                "clock" -> Arg.Clock(LocalTime.parse(o.getString("v")))
                "day" -> Arg.Day(LocalDate.parse(o.getString("v")))
                "money" -> Arg.Money(o.getLong("v"), o.optString("c").takeIf { it.length == 3 && it.all { c -> c in 'A'..'Z' } })
                "date" -> Arg.Date(LocalDate.parse(o.getString("v")))
                "month" -> o.getInt("v").takeIf { it in 1..12 }?.let { Arg.Month(o.getInt("y"), it) }
                "sub" -> fromJson(o.optJSONObject("v"), known)?.let { Arg.Sub(it) }
                "ui" -> o.getString("v").split('/').map { known(it) }.let { parts ->
                    if (parts.all { it != null }) Arg.Ui(UiLabel(parts.filterNotNull())) else Arg.Plain(o.optString("en"))
                }
                "android" -> AndroidPath.entries.firstOrNull { it.name == o.optString("v") }?.let { Arg.Android(it) }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

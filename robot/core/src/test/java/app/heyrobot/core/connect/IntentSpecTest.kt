// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C7, plan §3.5 and §7.1's IntentSpecTest: a hand-off's actions and schemes from the short list, typed extras only, the
 * signer pinned in connections.json, every slot encoded for its place; never a call Robot places, a URI grant, a
 * content:// or file:// address, or an extra Android reads as an Intent or a stream.
 */
class IntentSpecTest {
    private val text = OpParam(ParamType.TEXT, maxLen = 200)
    private fun valid(s: IntentSpec) = IntentSpecs.valid(s, ConnCheck::paramFits)
    private fun v(x: Any, t: ParamType = ParamType.TEXT, src: ValueSource = ValueSource.YOURS) = SkillValue.of(x, t, src)
    private fun app(s: IntentSpec) = HandOffApp("org.example.app", "b".repeat(64), s)

    @Test fun theFiveActionsOnlyAndNoneThatActsByItself() {
        assertEquals(listOf("android.intent.action.VIEW", "android.intent.action.SEND", "android.intent.action.SENDTO", "android.intent.action.DIAL",
            "android.intent.action.MAIN"), HandOffAction.entries.map { it.android })
        // (a) from the main session: no CALL, no CALL_EMERGENCY or CALL_PRIVILEGED, nothing Robot's own permissions would satisfy.
        for (a in listOf("call", "CALL", "call_emergency", "call_privileged", "android.intent.action.CALL", "delete", "install_package", "set_alarm"))
            assertNull(a, HandOffAction.of(a))
        assertTrue(HandOffAction.entries.none { "CALL" in it.android || "DELETE" in it.android || "INSTALL" in it.android })
    }

    @Test fun telOnlyOpensTheDialer() {
        val n = mapOf("number" to text)
        assertTrue(valid(IntentSpec(HandOffAction.DIAL, data = "tel:{{number}}", params = n)))
        assertTrue(valid(IntentSpec(HandOffAction.VIEW, data = "tel:{{number}}", params = n)))
        assertFalse(valid(IntentSpec(HandOffAction.SENDTO, data = "tel:{{number}}", params = n)))
        // DIAL takes tel: only.
        assertFalse(valid(IntentSpec(HandOffAction.DIAL, data = "sms:{{number}}", params = n)))
        assertFalse(valid(IntentSpec(HandOffAction.DIAL)))
    }

    @Test fun eachActionsShape() {
        assertTrue(valid(ConnFixtures.shareText))
        assertTrue(valid(ConnFixtures.viewPage))
        assertTrue(valid(IntentSpec(HandOffAction.MAIN)))
        assertTrue(valid(IntentSpec(HandOffAction.SENDTO, data = "smsto:{{to}}", extras = listOf(HandOffExtra("sms_body", ParamType.TEXT, "On my way, {{who}}")),
            params = mapOf("to" to text, "who" to text))))
        assertTrue(valid(IntentSpec(HandOffAction.VIEW, data = "geo:0,0?q={{place}}", params = mapOf("place" to text))))
        assertTrue(valid(IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/start")))
        // A scheme the app declares (the launch finds the app only when it does).
        assertTrue(valid(IntentSpec(HandOffAction.VIEW, data = "antennapod-subscribe:{{feed}}", params = mapOf("feed" to text))))
        // SEND is text/plain with no data; MAIN has neither; VIEW needs an address.
        assertFalse(valid(ConnFixtures.shareText.copy(mime = "image/*")))
        assertFalse(valid(ConnFixtures.shareText.copy(data = "https://example.org/")))
        assertFalse(valid(IntentSpec(HandOffAction.MAIN, data = "https://example.org/")))
        assertFalse(valid(IntentSpec(HandOffAction.MAIN, mime = "text/plain")))
        assertFalse(valid(IntentSpec(HandOffAction.VIEW)))
        assertFalse(valid(IntentSpec(HandOffAction.SENDTO, data = "https://example.org/x")))
    }

    @Test fun neverASchemeThatHandsOverAFileAnIntentOrPlainHttp() {
        // (b) from the main session: no content:// in the data, nor file://, intent:, android-app:, javascript:, data: or http.
        for (d in listOf("content://media/external/images/1", "file:///sdcard/a.txt", "intent://x#Intent;scheme=https;end", "android-app://org.example/https/x",
            "javascript:alert(1)", "data:text/html,hi", "http://example.org/", "CONTENT://media/1", "android.resource://org.example/raw/a", "blob:x", "about:blank"))
            assertFalse(d, valid(IntentSpec(HandOffAction.VIEW, data = d)))
    }

    @Test fun anHttpsHostIsFixed() {
        val p = mapOf("x" to text)
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "https://{{x}}/a", params = p)))
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org{{x}}", params = p)))
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "https://me@wiki.example.org/{{x}}", params = p)))
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "{{x}}", params = p)))
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "https:/wiki.example.org/{{x}}", params = p)))
        assertTrue(valid(IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org?q={{x}}", params = p)))
        // An address's own words are RFC 3986's characters only.
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/a b")))
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/a%zz")))
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/\n")))
    }

    @Test fun extrasAreTypedAndNeverACapability() {
        fun with(vararg e: HandOffExtra, p: Map<String, OpParam> = emptyMap()) = valid(IntentSpec(HandOffAction.MAIN, extras = e.toList(), params = p))
        assertTrue(with(HandOffExtra("org.example.COUNT", ParamType.INTEGER, "12"), HandOffExtra("org.example.QUIET", ParamType.YES_NO, "true")))
        assertTrue(with(HandOffExtra("org.example.COUNT", ParamType.INTEGER, "{{n}}"), p = mapOf("n" to OpParam(ParamType.INTEGER, min = 1.0, max = 9.0))))
        // (b) never an extra Android reads as a stream, an intent, a result channel or who sent it, even as text.
        for (n in listOf("android.intent.extra.STREAM", "android.intent.extra.INTENT", "android.intent.extra.INITIAL_INTENTS", "android.intent.extra.REFERRER",
            "android.intent.extra.RESULT_RECEIVER", "android.intent.extra.CHOOSER_TARGETS"))
            assertFalse(n, with(HandOffExtra(n, ParamType.TEXT, "x")))
        // Only text, whole numbers and switches: a number, a choice or anything else isn't an extra's type.
        assertFalse(with(HandOffExtra("org.example.N", ParamType.NUMBER, "1.5")))
        assertFalse(with(HandOffExtra("org.example.N", ParamType.CHOICE, "a")))
        assertFalse(with(HandOffExtra("org.example.N", ParamType.INTEGER, "1.5")))
        assertFalse(with(HandOffExtra("org.example.N", ParamType.YES_NO, "yes")))
        assertFalse(with(HandOffExtra("org.example.N", ParamType.INTEGER, "{{t}}"), p = mapOf("t" to text)))
        // (b) a string naming a content:// or file:// address, as you typed it.
        assertFalse(with(HandOffExtra("android.intent.extra.TEXT", ParamType.TEXT, "see content://media/external/1")))
        assertFalse(with(HandOffExtra("android.intent.extra.TEXT", ParamType.TEXT, "FILE:///sdcard/x")))
        assertFalse(with(HandOffExtra("bad name", ParamType.TEXT, "x")))
        assertFalse(with(HandOffExtra("a", ParamType.TEXT, "x"), HandOffExtra("a", ParamType.TEXT, "y")))
        assertFalse(with(*Array(IntentSpecs.MAX_EXTRAS + 1) { HandOffExtra("org.example.E$it", ParamType.TEXT, "x") }))
    }

    @Test fun everySlotIsAParamAndEveryParamIsUsed() {
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/{{x}}")))
        assertFalse(valid(ConnFixtures.viewPage.copy(params = ConnFixtures.viewPage.params + ("spare" to text))))
        // A switch can't sit in an address.
        assertFalse(valid(IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/{{on}}", params = mapOf("on" to OpParam(ParamType.YES_NO)))))
        // A switch can't sit inside words either.
        assertFalse(valid(IntentSpec(HandOffAction.MAIN, extras = listOf(HandOffExtra("a", ParamType.TEXT, "on: {{on}}")), params = mapOf("on" to OpParam(ParamType.YES_NO)))))
    }

    @Test fun eachValueIsEncodedForItsPlace() {
        val view = app(ConnFixtures.viewPage)
        assertEquals("https://wiki.example.org/page/Caf%C3%A9%20%22du%22%20%7B%7Bx%7D%7D%20100%25", IntentSpecs.build(view, mapOf("page" to v("Café \"du\" {{x}} 100%")))!!.data)
        // Never a "/", "..", a line break or NUL: a value can't climb out of its place.
        for (bad in listOf("../admin", "a/b", "two\nlines", "nul\u0000", "back\\slash")) assertNull(bad, IntentSpecs.build(view, mapOf("page" to v(bad))))
        // A value starting with "-" is only text here: encoded, never an option.
        assertEquals("https://wiki.example.org/page/-rf", IntentSpecs.build(view, mapOf("page" to v("-rf")))!!.data)
        val share = app(ConnFixtures.shareText)
        val i = IntentSpecs.build(share, mapOf("text" to v("On my way {{eta}}")))!!
        assertEquals(listOf("android.intent.extra.TEXT" to "On my way {{eta}}"), i.extras)
        assertEquals("android.intent.action.SEND", i.action)
        assertEquals("text/plain", i.mime)
        assertNull(i.data)
        assertEquals("org.example.app", i.pkg)
        // (b) a value that brings a content:// address into an extra is refused when it is built.
        assertNull(IntentSpecs.build(share, mapOf("text" to v("content://media/external/images/1", src = ValueSource.ONLINE))))
        assertNull(IntentSpecs.build(share, mapOf("text" to v("a\u0000b"))))
    }

    /**
     * C12's L5: a message's slot isn't a path, so "On my way..." and "back at 3/4" go, encoded ("/" as %2F); a slot in an
     * address's path still never holds "/" or "..".
     */
    @Test fun anSmsBodyMayEndInDots() {
        val sms = app(IntentSpec(HandOffAction.SENDTO, data = "smsto:{{who}}?body={{msg}}", params = linkedMapOf("who" to text, "msg" to text)))
        assertTrue(valid(sms.intent))
        fun data(msg: String) = IntentSpecs.build(sms, mapOf("who" to v("5551234"), "msg" to v(msg)))?.data
        assertEquals("smsto:5551234?body=On%20my%20way...", data("On my way..."))
        assertEquals("smsto:5551234?body=back%20at%203%2F4", data("back at 3/4"))
        assertEquals("smsto:5551234?body=See%20you%20soon...%20%3F%23%26", data("See you soon... ?#&"))
        assertNull(data("two\nlines"))
        // An https address's query takes them too; its path still doesn't.
        val search = app(IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/search?q={{q}}", params = mapOf("q" to text)))
        assertEquals("https://wiki.example.org/search?q=3%2F4%20..", IntentSpecs.build(search, mapOf("q" to v("3/4 ..")))!!.data)
        for (bad in listOf("On my way...", "3/4", "..")) assertNull(bad, IntentSpecs.build(app(ConnFixtures.viewPage), mapOf("page" to v(bad))))
        // An app's own address with a path keeps the path's rule.
        val own = app(IntentSpec(HandOffAction.VIEW, data = "myapp://open/{{x}}?n={{y}}", params = linkedMapOf("x" to text, "y" to text)))
        assertTrue(valid(own.intent))
        assertNull(IntentSpecs.build(own, mapOf("x" to v(".."), "y" to v("a"))))
        assertEquals("myapp://open/a?n=..%2Fb", IntentSpecs.build(own, mapOf("x" to v("a"), "y" to v("../b")))!!.data)
    }

    @Test fun typedExtrasKeepTheirTypes() {
        val s = IntentSpec(HandOffAction.MAIN, extras = listOf(HandOffExtra("org.example.N", ParamType.INTEGER, "{{n}}"), HandOffExtra("org.example.Q", ParamType.YES_NO, "{{q}}"),
            HandOffExtra("org.example.T", ParamType.TEXT, "{{n}} left"), HandOffExtra("org.example.FIXED", ParamType.INTEGER, "7")),
            params = linkedMapOf("n" to OpParam(ParamType.INTEGER, min = 0.0, max = 9.0), "q" to OpParam(ParamType.YES_NO)))
        assertTrue(valid(s))
        val i = IntentSpecs.build(app(s), mapOf("n" to v(3L, ParamType.INTEGER), "q" to v(true, ParamType.YES_NO)))!!
        assertEquals(listOf("org.example.N" to 3L, "org.example.Q" to true, "org.example.T" to "3 left", "org.example.FIXED" to 7L), i.extras)
        assertTrue(i.launcher)
        assertTrue(i.extras.all { (_, x) -> x is String || x is Long || x is Boolean })
    }

    @Test fun effects() {
        assertEquals(setOf(SkillEffect.OPENS_APP, SkillEffect.MESSAGES_SOMEONE), IntentSpecs.effects(ConnFixtures.shareText))
        assertEquals(setOf(SkillEffect.OPENS_APP, SkillEffect.MESSAGES_SOMEONE), IntentSpecs.effects(IntentSpec(HandOffAction.VIEW, data = "smsto:{{to}}")))
        assertEquals(setOf(SkillEffect.OPENS_APP, SkillEffect.MESSAGES_SOMEONE), IntentSpecs.effects(IntentSpec(HandOffAction.VIEW, data = "mailto:{{to}}")))
        assertEquals(setOf(SkillEffect.OPENS_APP), IntentSpecs.effects(ConnFixtures.viewPage))
        assertEquals(setOf(SkillEffect.OPENS_APP), IntentSpecs.effects(IntentSpec(HandOffAction.DIAL, data = "tel:{{n}}")))
    }

    @Test fun connectionsJsonKeepsThePinAndTheIntent() {
        val c = ConnFixtures.signal()
        val back = (ConnectionCodec.decode(ConnectionCodec.encode(listOf(c, ConnFixtures.signal("c-app002", ConnFixtures.viewPage)))) as ConnFile.Ok)
        assertEquals(0, back.dropped)
        assertEquals(listOf(c, ConnFixtures.signal("c-app002", ConnFixtures.viewPage)), back.connections)
        // A hand-off without its intent, or with one that breaks a rule, is dropped whole.
        val noIntent = ConnJson.write(linkedMapOf("v" to 1L, "connections" to listOf(ConnectionCodec.toJson(c) - "intent")))
        assertEquals(1, (ConnectionCodec.decode(noIntent) as ConnFile.Ok).dropped)
        val call = ConnectionCodec.encode(listOf(ConnFixtures.signal(spec = IntentSpec(HandOffAction.DIAL, data = "tel:5550100")))).replace("\"dial\"", "\"call\"")
        assertEquals(1, (ConnectionCodec.decode(call) as ConnFile.Ok).dropped)
        val content = ConnectionCodec.encode(listOf(ConnFixtures.signal(spec = ConnFixtures.viewPage.copy(data = "https://wiki.example.org/x")))).replace("https://wiki.example.org/x", "content://x")
        assertEquals(1, (ConnectionCodec.decode(content) as ConnFile.Ok).dropped)
        // A key typed into a hand-off's words never goes in connections.json.
        val keyed = ConnFixtures.signal(spec = IntentSpec(HandOffAction.VIEW, data = "https://wiki.example.org/?token=sk-ant-api03-" + "x".repeat(40)))
        assertTrue(ConnCheck.problems(keyed).contains(ConnProblem.SECRET_VALUE))
    }

    @Test fun linksGiveTheSlotsTheirTypesAndWhichAreRequired() {
        val withDefault = ConnFixtures.shareText.copy(params = mapOf("text" to OpParam(ParamType.TEXT, maxLen = 500, default = "On my way.")))
        val links = ConnLinks(listOf(ConnFixtures.signal(), ConnFixtures.signal("c-app002", withDefault), ConnFixtures.homeAssistant()))
        val h = links.handOff("c-app001")!!
        assertEquals(setOf("text"), h.slots.keys)
        assertEquals(setOf("text"), h.required)
        assertEquals(setOf(SkillEffect.OPENS_APP, SkillEffect.MESSAGES_SOMEONE), h.effects)
        assertEquals(emptySet<String>(), links.handOff("c-app002")!!.required)
        assertNull(links.handOff(ConnFixtures.homeAssistant().id))
        assertNull(links.handOff("c-zzz999"))
        assertNotNull(links.handOff("c-app002"))
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Your answer of 4 Nov 2026 (plan §6, question 2): an MCP server is on your home network, on this phone or reached by its
// Tailscale address, never the public internet in 1.3, a home server behind a public address included. A typed address
// and a stored Test result are judged here (ConnRules.refusal, ConnCheck); a name's lookup and the socket in helpers'
// ConnHttp (McpClientTest).
class McpPublicTest {
    private fun mcp(address: String, where: ConnWhere? = null) =
        Connection("c-mcp001", ConnKind.MCP, "Home", ConnUrl.parse(address)!!, auth = ConnAuth.Bearer, where = where)

    @Test fun aPublicAddressIsRefusedForMcpOnly() {
        for (a in listOf("https://1.1.1.1/mcp", "https://[2606:4700:4700::1111]/mcp", "https://[::ffff:1.1.1.1]/mcp")) {
            assertEquals(a, ConnFailure.MCP_PUBLIC, ConnRules.refusal(mcp(a)))
            assertTrue(a, ConnProblem.MCP_PUBLIC in ConnCheck.problems(mcp(a)))
        }
        // Other kinds may still use the internet over https (D1).
        val hook = Connection("c-hook01", ConnKind.WEBHOOK, "Hook", ConnUrl.parse("https://1.1.1.1/x")!!)
        assertNull(ConnRules.refusal(hook))
    }

    @Test fun aServerTestedOnTheInternetIsRefusedByName() {
        assertEquals(ConnFailure.MCP_PUBLIC, ConnRules.refusal(mcp("https://mcp.a-company.example/", ConnWhere.INTERNET)))
        // A name not tested yet: its lookup decides (ConnHttp).
        assertNull(ConnRules.refusal(mcp("https://mcp.a-company.example/")))
    }

    @Test fun homeTheTailnetAndThisPhoneStayAllowed() {
        for (a in listOf("https://100.101.102.103/mcp", "https://box.tail1234.ts.net/mcp", "https://192.168.1.5:8123/api/mcp",
            "https://127.0.0.1:9000/mcp", "https://[fd12:3456::5]/mcp")) {
            assertNull(a, ConnRules.refusal(mcp(a)))
            assertNull(a, ConnRules.refusal(mcp(a, ConnWhere.HOME)))
            assertFalse(a, ConnProblem.MCP_PUBLIC in ConnCheck.problems(mcp(a)))
        }
    }

    @Test fun theLineSaysWhyAndWhatIsAllowed() {
        val en = ConnFailure.MCP_PUBLIC.text("Jarvis").render(Langs.EN)
        assertTrue(en, "internet" in en && "home network" in en && "Tailscale" in en && "Jarvis" in en)
        val fr = ConnFailure.MCP_PUBLIC.text("Jarvis").render(Langs.of(Lang.FR))
        assertTrue(fr, "Internet" in fr && "réseau domestique" in fr && "Tailscale" in fr && "Jarvis" in fr)
    }
}

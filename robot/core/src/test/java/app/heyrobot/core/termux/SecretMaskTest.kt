// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SecretMask (B7.5): each pattern, PEM blocks across lines, Robot's own keys, and no false masks on hashes and UUIDs. */
class SecretMaskTest {
    private val ant = "sk-ant-api03-" + "Ab0_-".repeat(10)

    @Test fun eachPatternIsHidden() {
        val samples = mapOf(
            ant to SecretMask.Kind.API_KEY,
            "sk-proj-" + "a1B2".repeat(10) to SecretMask.Kind.API_KEY,
            "sk-or-v1-" + "f".repeat(64) to SecretMask.Kind.API_KEY,
            "xai-" + "Q".repeat(40) to SecretMask.Kind.API_KEY,
            "gsk_" + "z".repeat(40) to SecretMask.Kind.API_KEY,
            "sk-" + "A1".repeat(24) to SecretMask.Kind.API_KEY,
            "AIza" + "S".repeat(35) to SecretMask.Kind.API_KEY,
            "AKIAIOSFODNN7EXAMPLE" to SecretMask.Kind.API_KEY,
            "ghp_" + "x".repeat(36) to SecretMask.Kind.TOKEN,
            "github_pat_" + "1".repeat(22) + "_" + "a".repeat(59) to SecretMask.Kind.TOKEN,
            "glpat-" + "y".repeat(20) to SecretMask.Kind.TOKEN,
            "xoxb-1234567890-abcdefghij" to SecretMask.Kind.TOKEN,
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U" to SecretMask.Kind.TOKEN,
        )
        for ((secret, kind) in samples) {
            val text = "before $secret after"
            val found = SecretMask.find(text)
            assertEquals(secret, 1, found.size)
            assertEquals(secret, kind, found[0].kind)
            assertEquals("before [hidden: ${kind.en}] after", SecretMask.mask(text))
        }
        assertEquals("[masqué : ressemble à une clé d'API]", SecretMask.mask(ant, fr = true))
    }

    @Test fun passwordsAfterKeywordsAndInAddresses() {
        assertEquals("password=[hidden: looks like a password] next", SecretMask.mask("password=hunter22! next"))
        assertEquals("DB_PASSWORD: [hidden: looks like a password]", SecretMask.mask("DB_PASSWORD: \"correct horse\""))
        assertEquals("aws_secret_access_key = [hidden: looks like a password]", SecretMask.mask("aws_secret_access_key = wJalrXUtnFEMI/K7MDENG"))
        assertEquals("{\"api_key\": [hidden: looks like a password]}", SecretMask.mask("{\"api_key\": \"0123456789abcdef\"}"))
        assertEquals("https://me:[hidden: looks like a password]@github.com/x.git", SecretMask.mask("https://me:hunter22@github.com/x.git"))
        assertEquals("Authorization: Bearer [hidden: looks like a token]", SecretMask.mask("Authorization: Bearer abcdefghijklmnop1234"))
        // Not secrets: small numbers, variables, placeholders.
        for (s in listOf("tokens: 1500", "Max tokens = 4096", "password=\$PASS", "token: <your token>", "password=******", "secret: null", "passwords: 3",
                "token=abc", "password: required", "https://example.com:8080/path", "user@host:~/x")) {
            assertEquals(s, s, SecretMask.mask(s))
        }
    }

    @Test fun privateKeyBlocksAcrossLines() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\nQyNTUxOQAAACD=\n-----END OPENSSH PRIVATE KEY-----"
        assertEquals("cat says:\n[hidden: looks like a private key]\ndone", SecretMask.mask("cat says:\n$key\ndone"))
        assertEquals("[hidden: looks like a private key]", SecretMask.mask("-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----"))
        assertEquals("[hidden: looks like a private key]", SecretMask.mask("-----BEGIN PGP PRIVATE KEY BLOCK-----\nxyz\n-----END PGP PRIVATE KEY BLOCK-----"))
        // Cut off before its end: hidden to the end anyway.
        assertEquals("x [hidden: looks like a private key]", SecretMask.mask("x -----BEGIN EC PRIVATE KEY-----\nMHcCAQEE"))
        // Its BEGIN line cut off (Termux keeps the end of long output): everything before its END line is hidden.
        assertEquals("[hidden: looks like a private key]\nnext", SecretMask.mask("AAAAC3Nz\n-----END RSA PRIVATE KEY-----\nnext"))
        assertEquals("x\n-----END CERTIFICATE-----", SecretMask.mask("x\n-----END CERTIFICATE-----"))
        // A public key or certificate isn't secret.
        val cert = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
        assertEquals(cert, SecretMask.mask(cert))
        val pub = "-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----"
        assertEquals(pub, SecretMask.mask(pub))
    }

    @Test fun robotsOwnKeys() {
        val own = listOf("my-own-brave-token-1234")
        assertEquals("x [hidden: one of Robot's own keys] y", SecretMask.mask("x my-own-brave-token-1234 y", own))
        assertTrue(SecretMask.hasOwn("curl -H 'X: my-own-brave-token-1234'", own))
        assertFalse(SecretMask.hasOwn("curl x", own))
        // Too short to search for.
        assertFalse(SecretMask.hasOwn("abc", listOf("abc")))
        assertTrue(SecretMask.hasRobotKey("curl -H 'x-api-key: $ant' https://api.anthropic.com"))
        assertTrue(SecretMask.hasRobotKey("Authorization: Bearer sk-proj-" + "a".repeat(30)))
        // 1.0: GitHub's tokens too (the updater's token is one; :voice refuses it by its shape).
        for (t in listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_")) assertTrue(t, SecretMask.hasRobotKey("git push https://me:$t" + "x".repeat(36) + "@github.com/me/x"))
        assertTrue(SecretMask.hasRobotKey("GITHUB_TOKEN=github_pat_11ABCDEFG0" + "a".repeat(70)))
        assertFalse(SecretMask.hasRobotKey("commit 8629e632fcb95da272221be327db653fb24befe9"))
        assertFalse(SecretMask.hasRobotKey("ghp_short"))
        assertTrue(SecretMask.hasRobotKey("my-own-brave-token-1234", own))
        assertTrue(SecretMask.ROBOT_KEY_PREFIXES.contains("sk-ant-"))
    }

    @Test fun noFalseMasks() {
        for (s in listOf(
            "3f2a9c1d4b5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8  file.iso",
            "commit 8629e632fcb95da272221be327db653fb24befe9",
            "UUID=123e4567-e89b-12d3-a456-426614174000",
            "sha256:228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42",
            "risk-assessment-for-the-team-2026", "desk-antique-lamp", "task-1234567890123456789012345", "ask-ant-colony",
            "Filesystem Size Used Avail Use% Mounted on", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIH me@phone",
        )) assertEquals(s, s, SecretMask.mask(s))
    }

    @Test fun overlapsBecomeOne() {
        val text = "password=$ant"
        val found = SecretMask.find(text)
        assertEquals(1, found.size)
        assertTrue(found[0].robotShape)
        assertEquals("password=[hidden: looks like an API key]", SecretMask.mask(text))
    }

    @Test fun hostileInputStaysFast() {
        val inputs = listOf(
            "password".repeat(20_000), "password=" + "a".repeat(200_000), "a".repeat(200_000), "sk-ant-".repeat(30_000),
            "eyJ" + "A".repeat(100_000), "eyJaaaaaaaaa.".repeat(20_000), "https://" + "u".repeat(100_000) + ":" + "p".repeat(100_000),
            "-----BEGIN ".repeat(30_000), "-----BEGIN PRIVATE KEY-----".repeat(10_000), "bearer ".repeat(30_000), "token: ".repeat(30_000),
            "x://a:".repeat(30_000), "AKIA".repeat(50_000),
        )
        val start = System.nanoTime()
        for (s in inputs) SecretMask.mask(s, listOf("owned-secret-value"))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 10_000)
    }
}

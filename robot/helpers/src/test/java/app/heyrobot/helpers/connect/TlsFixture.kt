// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.connect

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * ConnHttpTest's TLS fixture (connections plan §7.2): a test CA of its own, a server certificate it signed (for home.test,
 * split.test, localhost and 127.0.0.1), and a self-signed one, made once per test run by the JDK's own keytool in a
 * temporary folder, read into memory and the folder deleted. Nothing is fetched (okhttp-tls isn't in the offline cache)
 * and no private key is committed: `*.p12` is gitignored, and test resources go to the public repository. Neither is
 * trusted by the JVM's CAs, as neither would be by Android's system CAs (D3).
 */
object TlsFixture {
    private const val PASS = "robot-test-fixture"
    private val pass = PASS.toCharArray()

    /** The server's context: its certificate signed by the test CA, with the CA in its chain. */
    val signed: SSLContext by lazy { server(stores.getValue("server")) }

    /** A server whose certificate signed itself (a Home Assistant with its own certificate: Q2's case). */
    val selfSigned: SSLContext by lazy { server(stores.getValue("self")) }

    /** A client trust manager that trusts the test CA only (what a test gives ConnHttp; the app never can). */
    val trust: X509TrustManager by lazy {
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setCertificateEntry("ca", stores.getValue("ca").getCertificate("ca"))
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    /** A client context with [trust]. */
    val trusting: SSLContext by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) } }

    /**
     * The CA-signed server's certificate chain and private key as PEM files in [dir] (for fake_ha.py's https, C5's desk
     * runs): written for one test run, into a temporary folder the caller deletes. The key is the fixture's own, made by
     * keytool this run; it is never committed and trusted by nothing but [trust].
     */
    fun pem(dir: File): Pair<File, File> {
        val ks = stores.getValue("server")
        val enc = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
        fun block(kind: String, der: ByteArray) = "-----BEGIN $kind-----\n" + enc.encodeToString(der) + "\n-----END $kind-----\n"
        val chain = ks.getCertificateChain("server").joinToString("") { block("CERTIFICATE", it.encoded) }
        val key = (ks.getKey("server", pass) as java.security.PrivateKey).encoded
        val cert = File(dir, "server.pem").apply { writeText(chain) }
        val keyFile = File(dir, "server.key").apply { writeText(block("PRIVATE KEY", key)) }
        return cert to keyFile
    }

    private fun server(ks: KeyStore): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private val stores: Map<String, KeyStore> by lazy {
        val dir = Files.createTempDirectory("robot-conn-tls").toFile()
        try {
            val san = "san=dns:home.test,dns:split.test,dns:localhost,ip:127.0.0.1"
            keytool(dir, "-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Robot test CA", "-ext", "bc:c",
                "-validity", "3650", "-keystore", "ca.p12", "-storetype", "PKCS12", "-storepass", PASS, "-keypass", PASS)
            keytool(dir, "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=home.test",
                "-validity", "3650", "-keystore", "server.p12", "-storetype", "PKCS12", "-storepass", PASS, "-keypass", PASS)
            keytool(dir, "-certreq", "-alias", "server", "-keystore", "server.p12", "-storepass", PASS, "-file", "server.csr")
            keytool(dir, "-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PASS, "-infile", "server.csr", "-outfile", "server.cer",
                "-rfc", "-validity", "3650", "-ext", san, "-ext", "eku=serverAuth")
            keytool(dir, "-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PASS, "-rfc", "-file", "ca.cer")
            keytool(dir, "-importcert", "-alias", "ca", "-file", "ca.cer", "-keystore", "server.p12", "-storepass", PASS, "-noprompt")
            keytool(dir, "-importcert", "-alias", "server", "-file", "server.cer", "-keystore", "server.p12", "-storepass", PASS, "-noprompt")
            keytool(dir, "-genkeypair", "-alias", "self", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=home.test", "-ext", san,
                "-validity", "3650", "-keystore", "self.p12", "-storetype", "PKCS12", "-storepass", PASS, "-keypass", PASS)
            listOf("ca", "server", "self").associateWith { n ->
                KeyStore.getInstance("PKCS12").apply { File(dir, "$n.p12").inputStream().use { load(it, pass) } }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun keytool(dir: File, vararg args: String) {
        val bin = File(System.getProperty("java.home"), "bin/keytool")
        check(bin.canExecute()) { "no keytool at $bin" }
        val p = ProcessBuilder(listOf(bin.path) + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "keytool ${args.first()}: $out" }
    }
}

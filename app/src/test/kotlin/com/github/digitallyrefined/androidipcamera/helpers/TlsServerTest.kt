package com.github.digitallyrefined.androidipcamera.helpers

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Verifies the TLS configuration and server socket creation. */
class TlsServerTest {
    private val protocol = "TLSv1.2"

    private class Material(val config: TlsServerConfig, val certificate: X509Certificate)

    private fun material(): Material {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Date()
        val subject = X500Name("CN=localhost")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            Date(now.time + 86_400_000L),
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("test", keyPair.private, "changeit".toCharArray(), arrayOf(certificate))
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "changeit".toCharArray())
        }.keyManagers
        val context = SSLContext.getInstance(protocol)
        context.init(keyManagers, null, null)
        return Material(TlsServerConfig(protocol, context.serverSocketFactory), certificate)
    }

    private fun trustAllContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        }
        return SSLContext.getInstance(protocol).apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }

    @Test
    fun `tls listener presents the configured certificate`() {
        val material = material()
        val listener = material.config.createServerSocket(0)
        val port = listener.localPort
        val failure = arrayOfNulls<Throwable>(1)
        val accepted = Thread {
            try {
                listener.accept().use { socket ->
                    socket.getInputStream().read()
                }
            } catch (e: Throwable) {
                failure[0] = e
            } finally {
                listener.close()
            }
        }.apply { isDaemon = true; start() }

        try {
            val client = trustAllContext().socketFactory.createSocket("127.0.0.1", port) as SSLSocket
            client.use { socket ->
                socket.soTimeout = 5_000
                socket.startHandshake()
                val peer = socket.session.peerCertificates.first() as X509Certificate
                assertTrue("server presented a different certificate", peer.encoded.contentEquals(material.certificate.encoded))
                assertEquals(protocol, socket.session.protocol)
                socket.outputStream.write(1)
                socket.outputStream.flush()
            }
        } finally {
            accepted.join(5_000)
        }
        failure[0]?.let { throw AssertionError("TLS accept failed", it) }
    }
}

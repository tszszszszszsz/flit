package app.flit.share.protocol.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyManager
import java.security.KeyManagerFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A self-signed device identity used for both roles:
 * server TLS listener and client certificate presented on mutual-TLS handshakes.
 *
 * The announced [fingerprint] is the uppercase-hex SHA-256 of the DER certificate,
 * exactly what official LocalSend servers verify in register/prepare.
 */
class TlsIdentity private constructor(
    val alias: String,
    val keyStore: KeyStore,
    val password: CharArray,
) {
    val certificate: X509Certificate get() = keyStore.getCertificate(alias) as X509Certificate

    /** Uppercase-hex SHA-256 of the certificate, announded as `fingerprint` on the wire. */
    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }
    }

    fun keyManagers(): Array<KeyManager> {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        return kmf.keyManagers ?: emptyArray()
    }

    /** One TLS context fits both roles: presents our key, trusts any peer cert. */
    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(keyManagers(), arrayOf(trustAllManager()), null)
    }

    fun save(file: File) {
        val fos: OutputStream = file.outputStream()
        try {
            keyStore.store(fos, password)
        } finally {
            fos.close()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "flit-device"
        const val DEFAULT_PASSWORD = "flit"

        fun generate(alias: String = DEFAULT_ALIAS): TlsIdentity {
            val password = DEFAULT_PASSWORD.toCharArray()
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=flit, O=Flit")
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now), // uniqueness within a second; good enough for a device cert
                now - TimeUnit.DAYS.toMillis(1),
                now + TimeUnit.DAYS.toMillis(365 * 30),
                name,
                keyPair.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            builder.addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign),
            )
            builder.addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)),
            )
            builder.addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(arrayOf(
                    GeneralName(GeneralName.dNSName, "localhost"),
                    GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                )),
            )
            val cert = JcaX509CertificateConverter().getCertificate(
                builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)),
            )
            cert.checkValidity()
            cert.verify(keyPair.public)

            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null)
            ks.setKeyEntry(alias, keyPair.private, password, arrayOf(cert))
            return TlsIdentity(alias, ks, password)
        }

        fun load(file: File, alias: String = DEFAULT_ALIAS): TlsIdentity {
            val password = DEFAULT_PASSWORD.toCharArray()
            val ks = KeyStore.getInstance("PKCS12")
            file.inputStream().use { ks.load(it, password) }
            try {
                val c = ks.getCertificate(alias) as X509Certificate
                c.checkValidity()
            } catch (e: CertificateException) {
                throw IllegalStateException("stored identity certificate is not valid: ${e.message}", e)
            }
            return TlsIdentity(alias, ks, password)
        }
    }
}

/** Trusts everything — peers are self-signed by design; identity is the announced fingerprint. */
fun trustAllManager(): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

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
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
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
    // Held as an immutable String: Ktor/netty zero-fill password arrays after use,
    // so every consumer must get its own fresh copy (see [secretChars]).
    private val secret: String,
) {
    val certificate: X509Certificate get() = keyStore.getCertificate(alias) as X509Certificate

    /** A fresh handle each time: the store owns the key, never a cached array. */
    private val privateKey: PrivateKey get() = keyStore.getKey(alias, secretChars()) as PrivateKey

    /** Uppercase-hex SHA-256 of the certificate, announced as `fingerprint` on the wire. */
    val fingerprint: String by lazy { certificateFingerprint(certificate) }

    /** Signs caller-supplied canonical bytes with this device's private key. */
    fun signPanic(canonical: ByteArray): String {
        val signer = Signature.getInstance(signatureAlgorithm(privateKey.algorithm))
        signer.initSign(privateKey)
        signer.update(canonical)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    /** Instance form of the fingerprint-pinned signature check (see companion). */
    fun verifyPanic(senderFp: String, canonical: ByteArray, sig: String): Boolean =
        verifyPanicSignature(senderFp, canonical, sig)

    /** Fresh mutable copy per call — consumers may zero it after use. */
    fun secretChars(): CharArray = secret.toCharArray()

    /**
     * Key managers whose client-cert selection ignores the peer's `certificate_authorities` list.
     *
     * LocalSend peers are self-signed with no CA hierarchy; v2 servers (rustls-based) list their
     * own subject DN as the only acceptable issuer. Honoring that list makes stock SunX509 select
     * NO certificate and send an empty client flight — then the peer kills the handshake with
     * `certificate_required` (rustls alert 116). Selection must match by key algorithm only.
     */
    fun keyManagers(): Array<KeyManager> {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, secretChars())
        val inner = kmf.keyManagers?.filterIsInstance<X509ExtendedKeyManager>()?.firstOrNull()
            ?: return kmf.keyManagers ?: emptyArray()
        val delegatePrivate = inner.getPrivateKey(alias)

        return arrayOf(object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(
                keyType: Array<out String>,
                issuers: Array<Principal>?,
                socket: Socket?,
            ): String = inner.chooseClientAlias(keyType, null, socket) ?: aliasIfUsable(keyType) ?: alias

            override fun chooseEngineClientAlias(
                keyType: Array<out String>?,
                issuers: Array<Principal>?,
                engine: SSLEngine?,
            ): String = inner.chooseEngineClientAlias(keyType, null, engine) ?: aliasIfUsable(keyType.orEmpty()) ?: alias

            override fun chooseServerAlias(
                keyType: String,
                issuers: Array<Principal>?,
                socket: Socket?,
            ): String = inner.chooseServerAlias(keyType, null, socket) ?: aliasIfUsable(arrayOf(keyType)) ?: alias

            override fun chooseEngineServerAlias(
                keyType: String,
                issuers: Array<Principal>?,
                engine: SSLEngine?,
            ): String = inner.chooseEngineServerAlias(keyType, null, engine) ?: aliasIfUsable(arrayOf(keyType)) ?: alias

            override fun getClientAliases(keyType: String, issuers: Array<Principal>?): Array<String> =
                inner.getClientAliases(keyType, null) ?: emptyArray()

            override fun getServerAliases(keyType: String, issuers: Array<Principal>?): Array<String> =
                inner.getServerAliases(keyType, null) ?: emptyArray()

            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                inner.getCertificateChain(alias) ?: emptyArray()

            override fun getPrivateKey(alias: String?): PrivateKey =
                if (alias == this@TlsIdentity.alias) delegatePrivate ?: inner.getPrivateKey(alias)
                else inner.getPrivateKey(alias)

            /** The stock KM may still fail its own issuer-free selection for exotic key types; ours is universal. */
            private fun aliasIfUsable(keyTypes: Array<out String>): String? {
                val alg = delegatePrivate?.algorithm ?: return null
                return if (keyTypes.any { it.equals(alg, ignoreCase = true) }) alias else null
            }
        })
    }

    /** One TLS context fits both roles: presents our key, trusts any peer cert. */
    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(keyManagers(), arrayOf(trustAllManager()), null)
    }

    fun save(file: File) {
        val fos: OutputStream = file.outputStream()
        try {
            keyStore.store(fos, secretChars())
        } finally {
            fos.close()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "flit-device"
        const val DEFAULT_PASSWORD = "flit"

        /**
         * Certificates trusted for panic signatures, keyed by the SHA-256 of
         * their DER — the very same value announced as a device fingerprint.
         * Pinning by the certificate's OWN digest (never a caller-supplied
         * label) is what makes a claimed senderFp unforgeable: an attacker's
         * cert lands under the attacker's fingerprint, not the victim's.
         */
        private val panicPins = ConcurrentHashMap<String, X509Certificate>()

        /** Pins [cert] under its own fingerprint; returns that fingerprint. */
        fun pin(cert: X509Certificate): String {
            val fp = certificateFingerprint(cert)
            panicPins[fp] = cert
            return fp
        }

        fun pinned(fingerprint: String): X509Certificate? = panicPins[fingerprint.trim().uppercase()]

        fun forgetPin(fingerprint: String) {
            panicPins.remove(fingerprint.trim().uppercase())
        }

        fun fingerprintOf(cert: X509Certificate): String = certificateFingerprint(cert)

        /** Static form of the pinned signature check, usable by PanicCore.verify. */
        fun verifyPanic(senderFp: String, canonical: ByteArray, sig: String): Boolean =
            verifyPanicSignature(senderFp, canonical, sig)

        fun generate(alias: String = DEFAULT_ALIAS): TlsIdentity {
            val password = DEFAULT_PASSWORD.toCharArray()
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=flit, O=Flit")
            val builder = JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now), // uniqueness within a second; good enough for a device cert
                Date(now - TimeUnit.DAYS.toMillis(1)),
                Date(now + TimeUnit.DAYS.toMillis(365L * 30)),
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
            return TlsIdentity(alias, ks, DEFAULT_PASSWORD)
        }

        fun load(file: File, alias: String = DEFAULT_ALIAS): TlsIdentity {
            val ks = KeyStore.getInstance("PKCS12")
            file.inputStream().use { ks.load(it, DEFAULT_PASSWORD.toCharArray()) }
            try {
                val c = ks.getCertificate(alias) as X509Certificate
                c.checkValidity()
            } catch (e: CertificateException) {
                throw IllegalStateException("stored identity certificate is not valid: ${e.message}", e)
            }
            return TlsIdentity(alias, ks, DEFAULT_PASSWORD)
        }
    }
}

/** Uppercase-hex SHA-256 of a certificate's DER encoding — the wire fingerprint. */
private fun certificateFingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256")
        .digest(cert.encoded)
        .joinToString("") { "%02X".format(it) }

/** RSA identities are what generate() mints; loaded EC keys stay usable too. */
private fun signatureAlgorithm(keyAlgorithm: String): String =
    if (keyAlgorithm.equals("EC", ignoreCase = true)) "SHA256withECDSA" else "SHA256withRSA"

/** Shared by the instance and companion verifyPanic entry points. */
private fun verifyPanicSignature(senderFp: String, canonical: ByteArray, sig: String): Boolean {
    val fp = senderFp.trim().uppercase()
    val cert = TlsIdentity.pinned(fp) ?: return false
    if (TlsIdentity.fingerprintOf(cert) != fp) return false
    return try {
        val verifier = Signature.getInstance(signatureAlgorithm(cert.publicKey.algorithm))
        verifier.initVerify(cert.publicKey)
        verifier.update(canonical)
        verifier.verify(Base64.getDecoder().decode(sig.trim()))
    } catch (_: Exception) {
        false // malformed base64, wrong algorithm, or plain forgery
    }
}

/** Trusts everything — peers are self-signed by design; identity is the announced fingerprint. */
fun trustAllManager(): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

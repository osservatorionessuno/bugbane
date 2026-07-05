package org.osservatorionessuno.cadb

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.osservatorionessuno.qf.crypto.AndroidKeystoreKeyVault
import org.osservatorionessuno.qf.crypto.AndroidKeystoreKeyVault.StrongBoxPolicy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        var key = readPrivateKeyFromFile(context)
        var cert = readCertificateFromFile(context)
        if (key == null) {
            val keyPair = generateKeyPair()
            key = keyPair.private
            writePrivateKeyToFile(context, key)
            cert = generateCertificate(keyPair)
            writeCertificateToFile(context, cert)
        }
        privateKey = key
        certificate = cert
            ?: throw IllegalStateException("ADB certificate missing for stored private key")
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "Bugbane"

    companion object {
        private const val TAG = "AdbConnectionManager"
        private const val ENCRYPTION_KEY_ALIAS = "bugbane_encryption_key"
        private const val CERT_FILE_NAME = "cert.pem"
        private const val PRIVATE_KEY_FILE = "private.key.encrypted"

        @Volatile
        private var instance: AdbConnectionManager? = null

        @JvmStatic
        @Throws(Exception::class)
        fun getInstance(context: Context): AdbConnectionManager {
            if (instance == null) {
                instance = AdbConnectionManager(context)
            }
            return instance!!
        }

        private fun generateKeyPair(): KeyPair {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            return keyPairGenerator.generateKeyPair()
        }

        private fun generateCertificate(keyPair: KeyPair): Certificate {
            val subject = X500Name("CN=Bugbane")
            val serialNumber = BigInteger(127, SecureRandom())
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 86_400_000L)

            val certBuilder = JcaX509v3CertificateBuilder(
                subject,
                serialNumber,
                notBefore,
                notAfter,
                subject,
                keyPair.public,
            )
            val extUtils = JcaX509ExtensionUtils()
            certBuilder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extUtils.createSubjectKeyIdentifier(keyPair.public),
            )

            val signer = JcaContentSignerBuilder("SHA512withRSA").build(keyPair.private)
            val certHolder = certBuilder.build(signer)
            return JcaX509CertificateConverter().getCertificate(certHolder)
        }

        private fun readCertificateFromFile(context: Context): Certificate? {
            val certFile = File(context.filesDir, CERT_FILE_NAME)
            if (!certFile.exists()) return null
            return FileInputStream(certFile).use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input)
            }
        }

        private fun writeCertificateToFile(context: Context, certificate: Certificate) {
            val certFile = File(context.filesDir, CERT_FILE_NAME)
            JcaPEMWriter(OutputStreamWriter(FileOutputStream(certFile), StandardCharsets.UTF_8)).use { writer ->
                writer.writeObject(certificate)
            }
        }

        private fun readPrivateKeyFromFile(context: Context): PrivateKey? {
            val privateKeyFile = File(context.filesDir, PRIVATE_KEY_FILE)
            if (!privateKeyFile.exists()) return null

            return try {
                val encryptedData = FileInputStream(privateKeyFile).use { it.readBytes() }
                val decryptedKeyData = adbKeyVault().unwrap(encryptedData)
                val keyFactory = KeyFactory.getInstance("RSA")
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(decryptedKeyData))
            } catch (e: Exception) {
                Log.w(TAG, "Could not decrypt stored ADB private key; will regenerate", e)
                null
            }
        }

        private fun writePrivateKeyToFile(context: Context, privateKey: PrivateKey) {
            try {
                val encryptedData = adbKeyVault().wrap(privateKey.encoded)
                FileOutputStream(File(context.filesDir, PRIVATE_KEY_FILE)).use { it.write(encryptedData) }
            } catch (e: Exception) {
                throw IOException("Failed to encrypt and write private key", e)
            }
        }

        private fun adbKeyVault(): AndroidKeystoreKeyVault =
            AndroidKeystoreKeyVault.getOrCreateKeyVault(ENCRYPTION_KEY_ALIAS, StrongBoxPolicy.PREFER)
    }
}

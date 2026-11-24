package com.cadfield.viewer.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seguridad en reposo y firma digital, con claves no exportables en Android Keystore
 * (respaldadas por hardware/StrongBox cuando el dispositivo lo permite).
 *
 *  - AES-256-GCM para cifrar blobs sensibles (fotos de campo, exportaciones, caché).
 *  - ECDSA P-256 (SHA256withECDSA) para firmar commits/versiones y PDFs de reporte.
 */
object CryptoManager {
    private const val KS = "AndroidKeyStore"
    private const val AES_ALIAS = "cadfield.aes.v1"
    private const val SIGN_ALIAS = "cadfield.sign.v1"
    private const val IV_LEN = 12

    private val keyStore by lazy { KeyStore.getInstance(KS).apply { load(null) } }

    private fun aesKey(): SecretKey {
        (keyStore.getEntry(AES_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        gen.init(
            KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    /** Cifra en streaming: [iv(12)][ciphertext+tag]. */
    fun encrypt(input: InputStream, output: OutputStream) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey()) }
        output.write(c.iv)
        CipherOutputStream(output, c).use { input.copyTo(it) }
    }

    fun decrypt(input: InputStream): InputStream {
        val iv = ByteArray(IV_LEN).also { require(input.read(it) == IV_LEN) { "Cabecera cifrada inválida" } }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, iv)) }
        return CipherInputStream(input, c)
    }

    private fun signingKey(): PrivateKey {
        (keyStore.getEntry(SIGN_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it.privateKey }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KS)
        gen.initialize(
            KeyGenParameterSpec.Builder(SIGN_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return gen.generateKeyPair().private
    }

    fun sign(payload: String): String {
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(signingKey()); update(payload.toByteArray()) }
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    /** Clave pública (X.509, Base64) para registrar en el servidor y verificar firmas. */
    fun publicKey(): String {
        signingKey()
        return Base64.encodeToString(keyStore.getCertificate(SIGN_ALIAS).publicKey.encoded, Base64.NO_WRAP)
    }
}

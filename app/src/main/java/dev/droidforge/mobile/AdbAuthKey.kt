package dev.droidforge.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.RSAPublicKey

/** Android Keystore backed ADB host key and legacy adbd RSA public-key encoding. */
internal class AdbAuthKey(context: Context) {
    private val alias = "droidforge-adb-host"
    private val pair: KeyPair

    init {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        pair = if (existing != null) KeyPair(existing.certificate.publicKey, existing.privateKey) else {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build()
            )
            generator.generateKeyPair()
        }
    }

    fun sign(token: ByteArray): ByteArray =
        Signature.getInstance("SHA1withRSA").run { initSign(pair.private); update(token); sign() }

    fun adbPublicKey(): ByteArray {
        val pub = pair.public as RSAPublicKey
        require(pub.modulus.bitLength() == 2048) { "ADB требует RSA ключ 2048 бит" }
        val n = littleEndian(pub.modulus, 256)
        val rr = littleEndian(BigInteger.ONE.shiftLeft(4096).mod(pub.modulus), 256)
        val n0 = ByteBuffer.wrap(n, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        val inverse = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32)).toLong()
        val out = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(64).putInt((-inverse).toInt()).put(n).put(rr).putInt(pub.publicExponent.toInt())
        val encoded = Base64.encodeToString(out.array(), Base64.NO_WRAP)
        return "$encoded DroidForge Android\n".toByteArray(Charsets.US_ASCII)
    }

    private fun littleEndian(value: BigInteger, size: Int): ByteArray {
        val big = value.toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
        val out = ByteArray(size)
        for (i in big.indices) out[i] = big[big.lastIndex - i]
        return out
    }
}

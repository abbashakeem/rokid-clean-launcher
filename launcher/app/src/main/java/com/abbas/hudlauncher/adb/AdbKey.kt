package com.abbas.hudlauncher.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * The launcher's own ADB identity, kept in private app storage. It is used to authenticate to adbd
 * over the loopback address so we can run commands as the shell user.
 */
class AdbKey(context: Context) {
    private val dir = File(context.filesDir, "adb").apply { mkdirs() }
    private val privFile = File(dir, "adbkey")
    private val pubFile = File(dir, "adbkey.pub")

    val keyPair: KeyPair = load() ?: generate()

    private fun load(): KeyPair? {
        if (!privFile.exists() || !pubFile.exists()) return null
        return try {
            val kf = KeyFactory.getInstance("RSA")
            KeyPair(
                kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())),
                kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes())),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun generate(): KeyPair {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        return kp
    }

    /**
     * adbd wants the key in Android's own RSAPublicKey struct, base64'd, followed by a name:
     *   uint32 modulus size in 32-bit words, uint32 n0inv, modulus[256] LE, rr[256] LE, uint32 exponent
     * n0inv is -1/n mod 2^32 and rr is (2^2048)^2 mod n, both for adbd's Montgomery arithmetic.
     */
    fun publicKeyForAdb(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val r32 = BigInteger.ZERO.setBit(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ZERO.setBit(KEY_BITS * 2).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(KEY_BITS / 32)
        buf.putInt(n0inv.toInt())
        buf.put(toLittleEndian(n, MODULUS_BYTES))
        buf.put(toLittleEndian(rr, MODULUS_BYTES))
        buf.putInt(pub.publicExponent.toInt())

        val encoded = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return (encoded + " hud-launcher" + NUL).toByteArray()
    }

    /**
     * The AUTH token from adbd is already a digest, so it is signed with PKCS#1 v1.5 padding over
     * the SHA-1 DigestInfo prefix plus the token, without hashing it a second time.
     */
    fun signToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private as RSAPrivateKey)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    /** BigInteger gives sign-extended big-endian; adbd wants fixed-width little-endian. */
    private fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(size)
        var src = be.size - 1
        var dst = 0
        while (src >= 0 && dst < size) {
            out[dst++] = be[src--]
        }
        return out
    }

    companion object {
        private const val KEY_BITS = 2048
        private const val MODULUS_BYTES = KEY_BITS / 8
        const val NUL = '\u0000'
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}

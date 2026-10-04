package schedule

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

object Crypto:
    private val random = SecureRandom()
    private val NonceBytes = 12

    def randomToken(bytes: Int = 32): String =
        val b = new Array[Byte](bytes)
        random.nextBytes(b)
        Base64.getUrlEncoder.withoutPadding.encodeToString(b)

    def sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString

    /** PKCE の code_challenge (S256)。 */
    def s256(verifier: String): String =
        Base64.getUrlEncoder.withoutPadding
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII")))

    /** AES-256-GCM。保存形式は base64(nonce || 暗号文 || 認証タグ)。 */
    final class Aead(key: Array[Byte]):
        require(key.length == 32, "TOKEN_ENCRYPTION_KEY must be 32 bytes (base64)")
        private val spec = SecretKeySpec(key, "AES")

        def encrypt(plain: String): String =
            val nonce = new Array[Byte](NonceBytes)
            random.nextBytes(nonce)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce))
            Base64.getEncoder.encodeToString(nonce ++ c.doFinal(plain.getBytes("UTF-8")))

        def decrypt(stored: String): String =
            val bytes = Base64.getDecoder.decode(stored)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, bytes.take(NonceBytes)))
            String(c.doFinal(bytes.drop(NonceBytes)), "UTF-8")

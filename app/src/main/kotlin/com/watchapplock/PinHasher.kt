package com.watchapplock

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 本地加盐 SHA-256 PIN 哈希。
 *
 * - salt 随机 16 字节，与哈希一并存于 [Prefs]
 * - 多轮迭代拉伸（10k 轮）增强抗暴力
 * - 不存明文，不联网
 */
object PinHasher {

    private const val ITERATIONS = 10_000

    fun hash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        var digest = md.digest(salt + pin.toByteArray(Charsets.UTF_8))
        repeat(ITERATIONS) {
            digest = md.digest(digest)
        }
        return toB64(digest)
    }

    /** 生成一次性盐（用于初始化 PIN 时）。 */
    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun toB64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromB64(b64: String): ByteArray? = runCatching {
        Base64.decode(b64, Base64.NO_WRAP)
    }.getOrNull()
}

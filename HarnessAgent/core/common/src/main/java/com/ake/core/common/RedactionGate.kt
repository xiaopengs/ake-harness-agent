package com.ake.core.common

/**
 * R1 加密通道 —— 只有接口，没有实现（数据库 §8.3）。
 *
 * **为什么实现不在这里**：Keystore 是 Android API。一旦把它放进 `:core`，
 * 这个模块就必须变成 Android 库，于是 L2/L4 的全部单测被迫改用 Robolectric，
 * 毫秒级反馈没了 —— 而毫秒级反馈是本项目能迭代的唯一前提（架构 §1.3）。
 *
 * 实现放在 Android 侧注入进来（见 `:app` 的 Hilt Module）。
 * 这里只冻结**信封格式**，因为它是**落磁盘上的字节_shape**，一旦有数据写进去就再也改不动了。
 *
 * 布局（严格对齐数据库 §12.1 `ENC_BLOB_LAYOUT`）：
 * ```
 * | ver:1B | iv:12B | ciphertext | tag:16B |
 * ```
 */
public interface FieldCipher {
    /** @param aad 附加认证数据。建议传 `taskId value` 的字节，可防止密文被跨行搬移。 */
    public fun seal(plain: ByteArray, aad: ByteArray? = null): ByteArray

    /** 解密失败返回 null（密钥已被销毁时必然走到这条路径 —— 见 §8.5 ①）。 */
    public fun open(blob: ByteArray, aad: ByteArray? = null): ByteArray?
}

public object SecretEnvelope {
    public const val VERSION: Byte = 1
    public const val IV_LEN: Int = 12
    public const val TAG_BITS: Int = 128
    public const val TAG_LEN: Int = TAG_BITS / 8 // 16
    /** 最小合法 blob 长度：ver + iv + tag（密文可为空串）。 */
    public const val MIN_LEN: Int = 1 + IV_LEN + TAG_LEN

    public fun frame(ver: Byte = VERSION, iv: ByteArray, ciphertextAndTag: ByteArray): ByteArray =
        byteArrayOf(ver) + iv + ciphertextAndTag

    public fun versionOf(blob: ByteArray): Byte = blob.firstOrNull() ?: 0

    public fun isValidShape(blob: ByteArray): Boolean = blob.size >= MIN_LEN
}

/**
 * 落盘唯一闸口（`PersistenceRedactionGate` 的 core 侧形态，数据库 §8.4 / F3(c)）。
 *
 * §8.4 的原话：**「`scrub()` 与 `sealField()` 是唯一出口，任何 DAO 调用之前的文本都必须过它，
 * 不得靠调用点自觉」**。放在这里是为了让 `:data:persist` 拿到的就是一个**已经没有旁路的工具**，
 * 而不是一份还需要大家自觉执行的规范。
 *
 * @param cipher 未提供时的 `sealField` 会抛 —— 宁可崩溃也绝不静默明文落盘。
 */
public class RedactionGate(private val cipher: FieldCipher? = null) {

    /** 返回「已脱敏的明文」。调用方自行决定要不要加密。 */
    public fun scrub(text: String): RedactionResult = Redactor.scrub(text)

    /**
     * 「脱敏 + 加密」一步到位。R1 字段一律走这个。
     * @return 密文 blob 与脱敏命中次数（次数字段相邻 paragraph §8.3(e) 要求写进 redaction_count）
     */
    public fun sealField(text: String, aad: ByteArray? = null): Pair<ByteArray, Int> {
        val c = cipher
            ?: error("RedactionGate.sealField called without FieldCipher: refusing to persist plaintext")
        val r = Redactor.scrub(text)
        return c.seal(r.text.toByteArray(Charsets.UTF_8), aad) to r.redactionCount
    }

    public fun openField(blob: ByteArray, aad: ByteArray? = null): String? {
        val c = cipher ?: return null
        return c.open(blob, aad)?.toString(Charsets.UTF_8)
    }

    public companion object {
        /** 明文-only 场景（如只需要计数、不落库）用这个零配置实例。 */
        public val SCRUB_ONLY: RedactionGate = RedactionGate(null)
    }
}

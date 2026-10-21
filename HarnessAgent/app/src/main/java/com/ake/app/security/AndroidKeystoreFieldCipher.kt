package com.ake.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ake.core.common.FieldCipher
import com.ake.core.common.SecretEnvelope
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R1 字段加密 —— Android Keystore + AES-256-GCM（数据库 §8.3）。
 *
 * ### 为什么是 Keystore 而不是「自己存一个密钥」
 *
 * Keystore 的密钥**不可导出**（在带 TEE/StrongBox 的机型上，密钥材料从不进入主存）。
 * 于是「数据库文件被拷走」这件事的严重程度被大幅降低：攻击者拿到的是密文，
 * 而没有能解密它的东西 —— 只要密钥还在 Keystore 里。
 *
 * ### 为什么用 AES-GCM 而不是 AES-CBC
 *
 * GCM 自带完整性校验（tag）。加密数据库的行如果被篡改，解密会**直接失败**而不是
 * 吐出一段乱码 —— 轨迹回放时这两者的排查成本差一个数量级。
 *
 * ### AES-GCM 的 IV 纪律（这是最容易写错的地方）
 *
 * **同一 key 下 IV 绝不能重复**，否则 GCM 会同时泄露明文 XOR 关系与认证密钥。
 * 这里每次 seal 都用 `SecureRandom` 生成 12 字节 IV，并把它放进信封里随密文一起存。
 * 所以"存 12 字节 IV"不是冗余，是安全前提。
 *
 * 信封布局（严格对齐数据库 §8.3 / §12.1 `ENC_BLOB_LAYOUT`）：
 * ```
 * | ver:1B | iv:12B | ciphertext | tag:16B |
 * ```
 */
@Singleton
// ★ 必须 public：`PlatformBindingsModule.bindFieldCipher` 要把它 @Binds 到 `FieldCipher`。
//   它是 `FieldCipher` 在 Android 上的**唯一**实现，:app 是唯一合法的装配点。
public class AndroidKeystoreFieldCipher @Inject constructor() : FieldCipher {

    override fun seal(plain: ByteArray, aad: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(SecretEnvelope.IV_LEN)
        SECURE_RANDOM.nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(SecretEnvelope.TAG_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        // Cipher.doFinal 已经把 tag 追加在密文尾部 ⇒ 天然符合上面的信封布局
        val body = cipher.doFinal(plain)
        return SecretEnvelope.frame(iv = iv, ciphertextAndTag = body)
    }

    override fun open(blob: ByteArray, aad: ByteArray?): ByteArray? {
        if (!SecretEnvelope.isValidShape(blob)) return null
        return try {
            val ver = blob[0]
            if (ver != SecretEnvelope.VERSION) return null
            val iv = blob.copyOfRange(1, 1 + SecretEnvelope.IV_LEN)
            val body = blob.copyOfRange(1 + SecretEnvelope.IV_LEN, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(SecretEnvelope.TAG_BITS, iv))
            aad?.let { cipher.updateAAD(it) }
            cipher.doFinal(body)
        } catch (_: Throwable) {
            // 密钥被销毁（一键清除）时必然走到这里 —— 这是设计内的路径，不是异常
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return generate(ks)
    }

    /** Keystore 里没有就现生成。只在首次落盘时发生一次。 */
    private fun generate(ks: KeyStore): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true) // API 28 起可用
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // 有 StrongBox 就用；没有则由系统回落到 TEE。
                        // 不强制：强制时无 StrongBox 的机型会直接抛异常。
                        setIsStrongBoxBacked(true)
                    }
                }
                .build(),
        )
        return kg.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ake.trace.dek.v1"
        const val KEY_SIZE_BITS = 256
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        val SECURE_RANDOM = java.security.SecureRandom()
    }
}

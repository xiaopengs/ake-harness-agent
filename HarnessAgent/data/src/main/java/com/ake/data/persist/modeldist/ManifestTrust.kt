package com.ake.data.persist.modeldist

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * manifest 的信任库（公钥钉扎）。
 *
 * ## 为什么客户端要做验签
 *
 * manifest 决定**从哪个 URL 拉 2.6 GB 的可执行内容、以什么 sha256 落盘**。
 * 它是这条链路上唯一被人（运维）频繁改动、又直接决定端侧加载什么的文件 ——
 * 中间人改一次 URL，就能让全网设备去下一个攻击者控制的模型。
 * 所以：**没有验签通过的 manifest 一律不落地**（fail-closed），宁可让用户暂时没模型可用，
 * 也不能拉一个来路不明的 2.6 GB。
 *
 * ## 为什么默认算法是 RSA-SHA256 而不是 Ed25519
 *
 * Ed25519 更短更快，但 `java.security.Signature("Ed25519")` 在 Android 上要到 **API 31**
 * 才由 Conscrypt 提供，而本工程 `minSdk = 28` —— 用了它，API 28~30 的机器会在
 * `Signature.getInstance` 上抛 `NoSuchAlgorithmException`，即「老机器永远装不上模型」。
 * RSA-SHA256 在 API 28 与 JDK 上都原生可用。等 minSdk 抬到 31 再换是一行改动。
 */
public fun interface ManifestSignatureVerifier {
    /** @param signedBytes 规范化后的 manifest 字节（见 [ManifestCanonicalForm]） */
    public fun verify(signedBytes: ByteArray, sig: ManifestSignature): Boolean
}

public fun interface ManifestTrustStore {
    /** 未登记的 keyId 返回 `null` ⇒ 调用方必须拒绝，不得降级为「放行」。 */
    public fun verifierFor(keyId: String): ManifestSignatureVerifier?
}

public object ManifestTrust {

    public const val ALG_RSA_SHA256: String = "RSA-SHA256"

    /** 空信任库：任何 manifest 都验不过。用作「还没配置公钥」时的安全默认值。 */
    public val TRUST_NOTHING: ManifestTrustStore = ManifestTrustStore { null }

    /** 由若干 `(keyId, verifier)` 钉成的信任库。支持多把钥匙以便轮换。 */
    public fun pinned(vararg entries: Pair<String, ManifestSignatureVerifier>): ManifestTrustStore {
        val map = entries.toMap()
        return ManifestTrustStore { keyId -> map[keyId] }
    }

    /**
     * RSA / SHA-256 验签器。
     *
     * @param subjectPublicKeyInfoBase64 **X.509 SubjectPublicKeyInfo** 的 DER（标准 base64），
     *   即 `openssl rsa -pubout -outform DER | base64` 的产物。只接受这种格式：
     *   裸 modulus 拼接（PKCS#1）会让 `X509EncodedKeySpec` 静默失败，而那种失败在真机上
     *   表现为「所有 manifest 都验签失败」，排查成本很高。
     */
    public fun rsaSha256Verifier(subjectPublicKeyInfoBase64: String): ManifestSignatureVerifier {
        val spec = X509EncodedKeySpec(Base64.getDecoder().decode(subjectPublicKeyInfoBase64))
        val publicKey = runCatching { KeyFactory.getInstance("RSA").generatePublic(spec) }.getOrNull()
        return ManifestSignatureVerifier { signedBytes, sig ->
            if (sig.alg != ALG_RSA_SHA256) return@ManifestSignatureVerifier false
            val key = publicKey ?: return@ManifestSignatureVerifier false
            val raw = runCatching { Base64.getDecoder().decode(sig.valueBase64) }.getOrNull()
                ?: return@ManifestSignatureVerifier false
            runCatching {
                Signature.getInstance("SHA256withRSA").apply {
                    initVerify(key)
                    update(signedBytes)
                }.verify(raw)
            }.getOrDefault(false)
        }
    }
}

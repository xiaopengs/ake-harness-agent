package com.ake.data.persist.modeldist

/**
 * manifest 的**信任锚**：`keyId -> X.509 SubjectPublicKeyInfo 的 DER（标准 base64）`。
 *
 * ## ★ 为什么公钥是源码常量，而 manifest 地址是构建期注入
 *
 * 这两样东西经常被当成同一类配置，它们不是。差别在于**谁有权决定端侧信任什么**：
 *
 *  | 项                 | 谁改            | 放进本文还是构建期注入                     |
 *  |--------------------|-----------------|--------------------------------------------|
 *  | [ALL] 里的公钥     | 换签名钥匙时      | **源码常量**（本文件）—— 见下方两条理由     |
 *  | `manifestUrl`      | 换 CDN / 分环境   | **构建期注入**（`data/build.gradle.kts`）   |
 *
 * 公钥必须落在源码里、被人 review，理由是：
 *
 *  1. **钉扎的定义就是「信任锚只能来自 APK 自身」**。公钥若也走 `local.properties`
 *     或 CI 环境变量，那么**跑构建的那台机器**就变成了信任锚的来源：一台被拿下的
 *     runner 可以把任意钥匙钉进去，而代码评审看不到任何 diff。
 *  2. **公钥本来就要随包分发、本来就在网上明着传**（docs/03 §2.3 明确写了
 *     「公钥是公开信息」）。它不需要保密 —— 需要保密的是它的另一半，那把私钥
 *     在运维的密钥管理服务里，**本仓库从不持有**（`.gitignore` 对 `.local/`、
 *     `*.pem`、`*.key` 有四条独立兜底）。
 *
 * 于是这里唯一合理的形态就是：**明明白白写在源码里，可以被人一眼看到并 review。**
 *
 * ## ⚠ 格式必须是 X.509 SubjectPublicKeyInfo，不能是 PKCS#1
 *
 * `ManifestTrust.rsaSha256Verifier` 用的是 `X509EncodedKeySpec`。喂给它一串裸 modulus
 * 或 PKCS#1 公钥时它**不会报错**（异常被 `runCatching` 吞掉），而是变成一个恒假验签器 ——
 * 真机表现是「所有 manifest 都验签失败」，而日志里只有一行 `MANIFEST_SIGNATURE_INVALID`，
 * 看不出是钥匙串错还是内容坏。这是本项目已知排查成本最高的坑之一。
 *
 * 所以 ○ 取得这只串的唯一配方是（也就是 `scripts/model_manifest.py keygen` 打印的那串）：
 *
 * ```bash
 * openssl rsa -in <私钥>.pem -pubout -outform DER | base64 -w0
 * ```
 *
 * ○ 上线前由 `ModelManifestConfigTest` 逐把解析一次并在 `KeyFactory` 上验真 ——
 *   拼错 / 换了格式 ⇒ **单测红**，不会等到用户拉不到模型才发现。
 *
 * ## 轮换怎么走（为什么这里支持多把钥匙）
 *
 * 单把钥匙会导致「换钥匙必须等全网客户端升完级才能切」。正确做法是三拍：
 *
 *  1. 在 [ALL] 里**同时**钉新旧两把（旧的原样保留，只加一行新 keyId）；
 *  2. 服务端切到新钥匙下发（`--key-id` 传新的那个）；
 *  3. 一个版本之后再摘掉旧的那行。
 *
 * 换句话说：**「换 CDN」是部署动作（构建期注入），「换钥匙」是发布动作（改本文件 + 发版）**。
 * 后者无法回避 —— 端侧信任 list 只能随包走，这正是钉扎要付的代价。
 */
public object ModelManifestPins {

    /**
     * 2026-10 · 韩渡川用 `scripts/model_manifest.py keygen --key-id model-ca-2026` 生成的
     * 签名钥匙对的**公钥**（RSA-2048）。私钥同名放在 `.local/manifest-signing-private.pem`
     * （本地）与运维密钥管理服务（生产），两者都不得进版本库。
     */
    public const val MODEL_CA_2026: String = "model-ca-2026"

    public const val MODEL_CA_2026_SPKI: String =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAz1h8CXs0v7BwxZI7mitRrxAu3UFZ5UUGrJot" +
            "orgFJcVfosDwIrZ2uWflJmzVIWOlTBUFYZy/oMwvqMp7mK+RoRH/sxvuiyI1SZPKzQ4I7swx3H33EE" +
            "B45WCeHQnW51KvAfG6++sx4HbI+GpPCLYp2qlP9dvOnrKE7LBDTin5vPe5sXrgPiUeRPJfgw065r+" +
            "W1Xjg3K3yvpWO26yqGNXyiz/IrONezLsQM+1aiglnvhYLXAqCa1zCxY7T0IEZmN1O9tff1DULStWv" +
            "esot4fnrFMwKS4AfQebJp3cWtwiVPi6HYBkqrupwe65Qn+n7nnAVoKN4k+HsvHUrZGE5QhfiiQIDA" +
            "QAB"

    /**
     * 端侧认的全部钥匙。 keyId 必须与服务端 `--key-id` 传的值逐字节一致。
     *
     * ⚠ **不要**内联成一个临时表达式：这里要能被 grep 到、能被 diff 看到。
     */
    public val ALL: Map<String, String> = mapOf(
        MODEL_CA_2026 to MODEL_CA_2026_SPKI,
    )
}

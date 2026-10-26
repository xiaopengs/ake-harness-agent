package com.ake.data.persist.modeldist

import com.ake.core.common.AkeLog
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 把「构建期注入的 manifest 地址」与「源码里钉死的公钥」拼成一份 [ModelManifestConfig]。
 *
 * ## 为什么需要一个工厂，而不是直接 `ModelManifestConfig(BuildConfig.X, ModelManifestPins.ALL)`
 *
 * 因为配置来源变得**不可信**了 —— 一旦 URL 由外部注入，它就有可能是空串、
 * 是 `http://`、是一串空格。这些形态在旧代码里根本不会出现（常量写死），
 * 而现在它们会真实到达 `ModelManifestSource`：
 *
 *  | 坏输入           | 不做处理的话会怎样                                     | 这里的处理           |
 *  |------------------|--------------------------------------------------------|----------------------|
 *  | 空串 / 纯空白     | `HttpURLConnection` 抛异常 ⇒ 「网络坏了」与「没配置」混成一种错误 | [UNCONFIGURED]，根本不联网 |
 *  | `http://…`       | 明文拉取 2.6 GB 可执行内容的地址 ⇒ **信任边界被搬走**       | [UNCONFIGURED]，拒绝启用   |
 *  | 钥匙串格式错      | `X509EncodedKeySpec` **静默失败** ⇒ 所有 manifest 恒验签失败 | 当场验真并丢弃坏钥匙       |
 *
 * ## ★★ 失败一律落到 [ModelManifestConfig.UNCONFIGURED]，这里**绝不抛异常**
 *
 * 这是 fail-closed 的三条要求同时成立的地方：
 *
 *  1. **明确拒绝**：没配置 ⇒ `ModelManifestSource.refresh()` 返回
 *     `MANIFEST_NOT_CONFIGURED`，`:llm` 的 `ensureCurrent` 原样上抛 ⇒
 *     **一个字节都不会下载**（`:llm` 的 `ManifestFailClosedTest` 与此 commit 新增的
 *     `ModelManifestConfigTest` 都断言了「`transfer` 从未被调用」）。
 *  2. **不悄悄放行**：任何一条校验不通过都落到同一个未配置对象，不存在
 *     「跳过某个检查继续用半份配置」的分支。
 *  3. **不崩掉**：注入值坏掉了是**部署问题**，不是用户的问题。让它变成一个
 *     `Severity.MEDIUM` 的错误码上抛，比在 Hilt 装配期抛一个
 *     `IllegalStateException` 让 App 白屏要好 ——
 *     后者会让用户连「本地已有的那份模型」都用不上。
 *
 * ## 与 `ModelManifestSource` 的既有 fail-closed 是两层，不是重复
 *
 * `ModelManifestSource` 只有在**运行时**才能知道流量到了没有（验签失败、版本回退）；
 * 本工厂在**装配期**就把「不可能是真的」的配置拦掉。上面表格第三行那个用例最能说明
 * 差别：坏钥匙串如果只在运行时遇到，表现是「所有用户同一时刻验签失败」；
 * 拦在这里，表现是「开工时丢掉那把坏钥匙 + 其余钥匙照常生效」。
 */
public object ModelManifestConfigFactory {

    /** RSA 公钥强度下限（bits）。低于此值的钥匙不该出现在信任 list 里。 */
    private const val MIN_RSA_KEY_BITS: Int = 2048

    /**
     * @param manifestUrl 构建期注入的 manifest 地址（`data/build.gradle.kts` → `BuildConfig`）。
     *   可以是空串 —— 那表示「本次构建没有接分发链路」，得到了等价于 [UNCONFIGURED] 的结果。
     * @param pinnedKeys 信任锚，默认取源码里钉死的 [ModelManifestPins.ALL]。做成参数是为了单测，
     *   生产代码**不要**传别的值 —— 公钥不是可配项。
     * @param bundledManifest 内置兜底，见 `ModelManifestConfig` 的同名字段。
     */
    public fun build(
        manifestUrl: String,
        pinnedKeys: Map<String, String> = ModelManifestPins.ALL,
        bundledManifest: String? = null,
    ): ModelManifestConfig {
        val url = manifestUrl.trim()

        if (url.isEmpty()) {
            AkeLog.w(TAG, "manifest 地址未注入 ⇒ 分发链路保持关闭（MANIFEST_NOT_CONFIGURED）")
            return ModelManifestConfig.UNCONFIGURED
        }
        // ★ 与 `HttpManifestTransport` 里那道 https 检查是同一条规则的两道闸：
        //   这道拦在装配期 —— 地址一旦是 http，「下载什么」就先于 TLS 被人决定了。
        if (!url.startsWith("https://")) {
            AkeLog.e(TAG, "manifest 地址必须是 https（拿到的是 $url）⇒ 拒绝启用，落回未配置")
            return ModelManifestConfig.UNCONFIGURED
        }

        val usableKeys = pinnedKeys.filter { (keyId, spki) -> isUsable(keyId, spki) }
        if (usableKeys.isEmpty()) {
            AkeLog.e(TAG, "没有任何一把可用的钉扎公钥 ⇒ 分发链路保持关闭")
            return ModelManifestConfig.UNCONFIGURED
        }
        val dropped = pinnedKeys.size - usableKeys.size
        if (dropped > 0) {
            AkeLog.w(TAG, "丢弃 $dropped 把无法解析的钉扎公钥（保留 ${usableKeys.size} 把）")
        }

        return ModelManifestConfig(
            manifestUrl = url,
            pinnedKeys = usableKeys,
            bundledManifest = bundledManifest,
        )
    }

    /**
     * 一把钥匙能不能用。**当场解析一遍**，而不是等它在真机上变成一次恒假验签。
     *
     * 见 `ModelManifestPins` 类头那条格式警告：`X509EncodedKeySpec` 对错误格式
     * 是「静默失败」的，所以「写到源码里」不等于「能用」—— 必须验。
     */
    private fun isUsable(keyId: String, spki: String): Boolean {
        if (keyId.isBlank()) {
            AkeLog.e(TAG, "钉扎公钥的 keyId 为空 ⇒ 丢弃")
            return false
        }
        val key = runCatching {
            val spec = X509EncodedKeySpec(Base64.getDecoder().decode(spki.trim()))
            KeyFactory.getInstance("RSA").generatePublic(spec) as? RSAPublicKey
        }.getOrElse { t ->
            AkeLog.e(
                TAG,
                "钉扎公钥无法解析（keyId=$keyId）：${t.message} ⇒ 丢弃。" +
                    "合法串由 `openssl rsa -pubout -outform DER | base64 -w0` 产生",
            )
            null
        }
        if (key == null) return false
        if (key.modulus.bitLength() < MIN_RSA_KEY_BITS) {
            AkeLog.e(TAG, "钉扎公钥强度不足（keyId=$keyId，${key.modulus.bitLength()} bit）⇒ 丢弃")
            return false
        }
        return true
    }

    private const val TAG: String = "ModelManifestCfg"
}

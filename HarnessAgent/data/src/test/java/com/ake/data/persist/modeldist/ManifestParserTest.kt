package com.ake.data.persist.modeldist

import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * manifest 解析：合法 / 非法 / 缺失字段 / 签名。
 *
 * ## 三条要钉住的语义
 *
 *  1. **解析成功 == 验签通过**。不存在「解析出了对象但签名没验」的中间态 ——
 *     调用方拿到的 `ModelManifest` 一定是验过签的（`ModelManifestParser` 头部注释）。
 *  2. **缺失字段与字段非法是两种 code**：前者说明服务端配置漏了，后者说明值不合法，
 *     两者的处置完全不同（一个等运维补，一个可能是被篡改）。
 *  3. **安全字段必须硬拒**：非 https 的 URL、非 64-hex 的 sha256。
 */
@RunWith(JUnit4::class)
class ManifestParserTest {

    private fun codeOf(outcome: Outcome<ModelManifest>): String? =
        (outcome as? Outcome.Err)?.error?.code

    private fun parse(json: String, trust: ManifestTrustStore = ModelDistFixtures.trust): Outcome<ModelManifest> =
        ModelManifestParser(trust).parse(json)

    // ──────────────────────────────────────────── 合法

    @Test
    fun `合法 manifest 解析出全部字段`() {
        val manifest = ModelDistFixtures.manifestOf(
            ModelDistFixtures.entry(revision = 5, sha256 = ModelDistFixtures.sha("a"), minApp = 100, maxApp = 199),
        )
        val outcome = parse(ModelDistFixtures.signedJson(manifest))

        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        val parsed = (outcome as Outcome.Ok).value
        assertThat(parsed.manifestVersion).isEqualTo(7L)
        assertThat(parsed.models).hasSize(1)
        val e = parsed.models.single()
        assertThat(e.modelId).isEqualTo(ModelDistFixtures.MODEL_ID)
        assertThat(e.variant).isEqualTo(ModelDistFixtures.VARIANT)
        assertThat(e.sizeBytes).isEqualTo(2_782_923_040L)
        assertThat(e.minAppVersionCode).isEqualTo(100)
        assertThat(e.maxAppVersionCode).isEqualTo(199)
        assertThat(e.state).isEqualTo(ModelEntryState.ACTIVE)
        assertThat(e.capabilities).containsExactly("TEXT", "TOOLS")
    }

    @Test
    fun `max_app_version_code 缺失与显式 null 等价`() {
        val json = ModelDistFixtures.document(
            models = ModelDistFixtures.entryJson(maxApp = null),
            signature = null,
        )
        // 字段校验在验签之前：这里没有签名块，会先因「缺签名」失败；
        // 为了单独验证「null 可接受」，补一个真签名
        val manifest = ModelDistFixtures.manifestOf(ModelDistFixtures.entry(maxApp = null))
        val outcome = parse(ModelDistFixtures.signedJson(manifest))
        assertThat((outcome as Outcome.Ok).value.models.single().maxAppVersionCode).isNull()
        assertThat(json).contains("null") // 夹具确实渲染成 null
    }

    @Test
    fun `未知字段被忽略而不是报错`() {
        val manifest = ModelDistFixtures.manifestOf(ModelDistFixtures.entry())
        val json = ModelDistFixtures.signedJson(manifest).replaceFirst("{", "{\"notes\":\"whatever\",")
        val outcome = parse(json)
        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun `大小写混乱的 sha256 归一化为小写且不影响验签`() {
        // 服务端应当发小写；这里故意发大写，验证「归一化发生在验签之前」——
        // 否则规范化字节会与服务端签的对不上，表现为「配置没改却全线验签失败」
        // 只把 sha256 这一个字段换成大写（其余字段必须与签名时的对象逐字一致，
        // 否则测的就是「字段不一致导致验签失败」而不是「大小写归一化」）
        val lower = ModelDistFixtures.manifestOf(ModelDistFixtures.entry(sha256 = "a".repeat(64)))
        val json = ModelDistFixtures.signedJson(lower)
            .replace("\"sha256\":\"${"a".repeat(64)}\"", "\"sha256\":\"${"A".repeat(64)}\"")
        assertThat((parse(json) as Outcome.Ok).value.models.single().sha256).isEqualTo("a".repeat(64))
    }

    @Test
    fun `models 顺序不影响验签结果`() {
        val a = ModelDistFixtures.entry(revision = 1, variant = "int4", sha256 = ModelDistFixtures.sha("a"))
        val b = ModelDistFixtures.entry(revision = 2, variant = "int8", sha256 = ModelDistFixtures.sha("b"))
        val m1 = ModelDistFixtures.manifestOf(a, b)
        val m2 = ModelDistFixtures.manifestOf(b, a)
        // 同一份签名字节对两种排列都有效 ⇒ 规范化对 models 做了排序
        val sig = ModelDistFixtures.sign(m1)
        assertThat(sig.valueBase64).isEqualTo(ModelDistFixtures.sign(m2).valueBase64)
        assertThat(parse(ModelDistFixtures.signedJson(m2))).isInstanceOf(Outcome.Ok::class.java)
    }

    // ──────────────────────────────────────────── 缺失字段

    @Test
    fun `缺失必填字段报 FIELD_MISSING`() {
        for (field in listOf("model_id", "sha256", "url", "size_bytes", "min_app_version_code")) {
            val json = ModelDistFixtures.document(models = ModelDistFixtures.entryJson(omit = setOf(field)))
            assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.MANIFEST_FIELD_MISSING)
        }
    }

    @Test
    fun `缺失 manifest_version 与 models 报 FIELD_MISSING`() {
        val noVersion = ModelDistFixtures.document(
            models = ModelDistFixtures.entryJson(),
            omit = setOf("manifest_version"),
        )
        val noModels = ModelDistFixtures.document(models = "", omit = setOf("models"))
        assertThat(codeOf(parse(noVersion))).isEqualTo(ModelDistErrorCodes.MANIFEST_FIELD_MISSING)
        assertThat(codeOf(parse(noModels))).isEqualTo(ModelDistErrorCodes.MANIFEST_FIELD_MISSING)
    }

    @Test
    fun `state 缺失时默认 ACTIVE`() {
        val manifest = ModelDistFixtures.manifestOf(ModelDistFixtures.entry())
        // 连前面的逗号一起去掉：StrictJson 拒绝尾逗号，留下 `,}` 会变成畸形 JSON
        val json = ModelDistFixtures.signedJson(manifest).replace(",\"state\":\"ACTIVE\"", "")
        assertThat((parse(json) as Outcome.Ok).value.models.single().state)
            .isEqualTo(ModelEntryState.ACTIVE)
    }

    // ──────────────────────────────────────────── 非法字段

    @Test
    fun `非 https 的 url 被拒绝`() {
        for (bad in listOf("http://cdn.ake.cn/a.litertlm", "file:///sdcard/a.litertlm", "content://x/a")) {
            val json = ModelDistFixtures.document(models = ModelDistFixtures.entryJson(url = bad))
            assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.MANIFEST_FIELD_INVALID)
        }
    }

    @Test
    fun `非法 sha256 被拒绝`() {
        for (bad in listOf("xyz", "a".repeat(63), "a".repeat(65), "g".repeat(64))) {
            val json = ModelDistFixtures.document(models = ModelDistFixtures.entryJson(sha256 = bad))
            assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.MANIFEST_FIELD_INVALID)
        }
    }

    @Test
    fun `非正数 size_bytes 与 context_window 被拒绝`() {
        val zeroSize = ModelDistFixtures.document(models = ModelDistFixtures.entryJson(sizeBytes = 0))
        val zeroCtx = ModelDistFixtures.document(models = ModelDistFixtures.entryJson(contextWindow = 0))
        assertThat(codeOf(parse(zeroSize))).isEqualTo(ModelDistErrorCodes.MANIFEST_FIELD_INVALID)
        assertThat(codeOf(parse(zeroCtx))).isEqualTo(ModelDistErrorCodes.MANIFEST_FIELD_INVALID)
    }

    @Test
    fun `max 小于 min 被拒绝`() {
        val json = ModelDistFixtures.document(models = ModelDistFixtures.entryJson(minApp = 200, maxApp = 199))
        assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.MANIFEST_FIELD_INVALID)
    }

    @Test
    fun `未知 state 被拒绝`() {
        val json = ModelDistFixtures.document(models = ModelDistFixtures.entryJson(state = "BANNED"))
        assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.MANIFEST_FIELD_INVALID)
    }

    @Test
    fun `空 models 数组被拒绝`() {
        val json = ModelDistFixtures.document(models = "")
        assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.MANIFEST_EMPTY)
    }

    @Test
    fun `同一 variant 出现两次被拒绝`() {
        val json = ModelDistFixtures.document(
            models = ModelDistFixtures.entryJson(revision = 1) + "," + ModelDistFixtures.entryJson(revision = 2),
        )
        assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.MANIFEST_DUPLICATE_VARIANT)
    }

    @Test
    fun `schema_version 不支持时报 SCHEMA_UNSUPPORTED`() {
        val json = ModelDistFixtures.document(models = ModelDistFixtures.entryJson(), schemaVersion = "99")
        assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.MANIFEST_SCHEMA_UNSUPPORTED)
    }

    @Test
    fun `畸形 JSON 报 INVALID_INPUT`() {
        assertThat((parse("{not json") as Outcome.Err).error.code).isEqualTo(AppError.CODE_INVALID_INPUT)
    }

    // ──────────────────────────────────────────── 签名

    @Test
    fun `没有签名块被拒绝`() {
        val json = ModelDistFixtures.document(models = ModelDistFixtures.entryJson())
        assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.SIGNATURE_MISSING)
    }

    @Test
    fun `未登记的 keyId 被拒绝`() {
        val manifest = ModelDistFixtures.manifestOf(ModelDistFixtures.entry())
        val json = ModelDistFixtures.signedJson(manifest).replace("model-ca-2026", "attacker-key")
        assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.SIGNATURE_KEY_UNKNOWN)
    }

    @Test
    fun `空信任库一律拒绝`() {
        val manifest = ModelDistFixtures.manifestOf(ModelDistFixtures.entry())
        assertThat(codeOf(parse(ModelDistFixtures.signedJson(manifest), ManifestTrust.TRUST_NOTHING)))
            .isEqualTo(ModelDistErrorCodes.SIGNATURE_KEY_UNKNOWN)
    }

    @Test
    fun `内容被篡改后签名失效`() {
        val manifest = ModelDistFixtures.manifestOf(ModelDistFixtures.entry())
        val json = ModelDistFixtures.signedJson(manifest)
            .replace("https://cdn.ake.cn", "https://evil.example")
        assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.SIGNATURE_INVALID)
    }

    @Test
    fun `签名值被换掉后失效`() {
        val manifest = ModelDistFixtures.manifestOf(ModelDistFixtures.entry())
        val json = ModelDistFixtures.signedJson(manifest)
        // 末尾形如 `...BASE64"}}` ⇒ 只动 base64 的最后 4 个字符，别碰引号与花括号
        val tampered = json.replaceRange(json.length - 8, json.length - 4, "AAAA")
        assertThat(codeOf(parse(tampered))).isEqualTo(ModelDistErrorCodes.SIGNATURE_INVALID)
    }

    @Test
    fun `未知算法报 ALG_UNSUPPORTED`() {
        val manifest = ModelDistFixtures.manifestOf(ModelDistFixtures.entry())
        val json = ModelDistFixtures.signedJson(manifest).replace("RSA-SHA256", "ED25519")
        assertThat(codeOf(parse(json))).isEqualTo(ModelDistErrorCodes.SIGNATURE_ALG_UNSUPPORTED)
    }
}

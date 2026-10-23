package com.ake.data.persist.modeldist

import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ModelAssetState
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * 模型分发链路的测试夹具。
 *
 * ★ 签名用的是**真 RSA 密钥对**（测试里现生成），不是「假装验过了」的桩。
 * 理由与 `FakeTaskDao` 那条一致：把验签桩成恒真之后，「验签失败必须拒绝 manifest」
 * 这条性质就**没有被执行到** —— 测试会绿，而它声称要保护的东西从来没被验证。
 */
internal object ModelDistFixtures {

    const val MODEL_ID: String = "gemma-4-e2b-it"
    const val VARIANT: String = "int4"
    const val SIZE: Long = 2_782_923_040L
    const val CTX: Int = 8192

    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    val trust: ManifestTrustStore = ManifestTrust.pinned(
        "model-ca-2026" to ManifestTrust.rsaSha256Verifier(
            Base64.getEncoder().encodeToString(keyPair.public.encoded),
        ),
    )

    fun parser(trustStore: ManifestTrustStore = trust): ModelManifestParser = ModelManifestParser(trustStore)

    fun sha(seed: String): String = seed.repeat(64 / seed.length).take(64).padEnd(64, '0')

    fun entry(
        revision: Long = 5L,
        sha256: String = sha("a"),
        minApp: Int = 100,
        maxApp: Int? = 199,
        state: ModelEntryState = ModelEntryState.ACTIVE,
        url: String = "https://cdn.ake.cn/gemma4-int4-r$revision.litertlm",
        version: String = "2026.10.$revision",
        modelId: String = MODEL_ID,
        variant: String = VARIANT,
        sizeBytes: Long = SIZE,
    ): ModelManifestEntry = ModelManifestEntry(
        modelId = modelId,
        variant = variant,
        revision = revision,
        version = version,
        url = url,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        minAppVersionCode = minApp,
        maxAppVersionCode = maxApp,
        contextWindow = CTX,
        minRamGb = 6,
        capabilities = listOf("TEXT", "TOOLS"),
        state = state,
    )

    fun manifestOf(
        vararg entries: ModelManifestEntry,
        manifestVersion: Long = 7L,
        generatedAt: Long = 1_700_000_000_000L,
    ): ModelManifest = ModelManifest(
        schemaVersion = ManifestSchema.CURRENT,
        manifestVersion = manifestVersion,
        generatedAt = generatedAt,
        models = entries.toList(),
        signature = null,
    )

    /** 真签名：覆盖 `ManifestCanonicalForm.render(manifest)` 的字节。 */
    fun sign(manifest: ModelManifest): ManifestSignature {
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(ManifestCanonicalForm.render(manifest))
        }.sign()
        return ManifestSignature(
            alg = ManifestTrust.ALG_RSA_SHA256,
            keyId = "model-ca-2026",
            valueBase64 = Base64.getEncoder().encodeToString(sig),
        )
    }

    /** 签名块的原始 JSON 文本（可塞进任意文档，用于「内容与签名分开构造」的用例）。 */
    fun signatureJson(manifest: ModelManifest): String {
        val sig = sign(manifest)
        return buildString {
            append('{')
            append("\"alg\":").append(quote(sig.alg)).append(',')
            append("\"key_id\":").append(quote(sig.keyId)).append(',')
            append("\"value\":").append(quote(sig.valueBase64))
            append('}')
        }
    }

    /** 由领域对象渲染出**可解析回同一对象**的原始 JSON（含签名）。 */
    fun signedJson(manifest: ModelManifest): String {
        val sig = sign(manifest)
        return buildString {
            append('{')
            append("\"schema_version\":").append(manifest.schemaVersion).append(',')
            append("\"manifest_version\":").append(manifest.manifestVersion).append(',')
            append("\"generated_at\":").append(manifest.generatedAt).append(',')
            append("\"models\":[")
            manifest.models.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append(
                    entryJson(
                        modelId = e.modelId,
                        variant = e.variant,
                        revision = e.revision,
                        version = e.version,
                        url = e.url,
                        sizeBytes = e.sizeBytes,
                        sha256 = e.sha256,
                        minApp = e.minAppVersionCode,
                        maxApp = e.maxAppVersionCode,
                        contextWindow = e.contextWindow,
                        minRamGb = e.minRamGb,
                        capabilities = e.capabilities,
                        state = e.state.name,
                    ),
                )
            }
            append(']').append(',')
            append("\"signature\":").append(signatureJson(manifest))
            append('}')
        }
    }

    /** 单个条目的原始 JSON。`omit` 里的字段整体不出现 —— 用于「缺失字段」用例。 */
    fun entryJson(
        modelId: String = MODEL_ID,
        variant: String = VARIANT,
        revision: Long = 5L,
        version: String = "2026.10.1",
        url: String = "https://cdn.ake.cn/gemma4-int4-r5.litertlm",
        sizeBytes: Long = SIZE,
        sha256: String = sha("a"),
        minApp: Int = 100,
        maxApp: Int? = 199,
        contextWindow: Int = CTX,
        minRamGb: Int = 6,
        capabilities: List<String> = listOf("TEXT"),
        state: String = "ACTIVE",
        omit: Set<String> = emptySet(),
    ): String {
        val fields = listOf(
            "model_id" to quote(modelId),
            "variant" to quote(variant),
            "revision" to revision.toString(),
            "version" to quote(version),
            "url" to quote(url),
            "size_bytes" to sizeBytes.toString(),
            "sha256" to quote(sha256),
            "min_app_version_code" to minApp.toString(),
            "max_app_version_code" to (maxApp?.toString() ?: "null"),
            "context_window" to contextWindow.toString(),
            "min_ram_gb" to minRamGb.toString(),
            "capabilities" to capabilities.joinToString(",", "[", "]") { quote(it) },
            "state" to quote(state),
        ).filter { it.first !in omit }
        return fields.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + v }
    }

    /** 拼一份「models 数组 + 可选签名块」的完整文档，供畸形用例直接塞非法文本。 */
    fun document(
        models: String,
        schemaVersion: String = ManifestSchema.CURRENT.toString(),
        manifestVersion: String = "7",
        signature: String? = null,
        omit: Set<String> = emptySet(),
    ): String {
        val head = listOf(
            "schema_version" to schemaVersion,
            "manifest_version" to manifestVersion,
            "generated_at" to "1700000000000",
            "models" to "[$models]",
        ).filter { it.first !in omit }
        val body = head.joinToString(",") { (k, v) -> quote(k) + ":" + v }
        val sig = signature?.let { ",\"signature\":$it" } ?: ""
        return "{$body$sig}"
    }

    fun record(
        id: String = "01JASSET0000000000000000",
        sha256: String = sha("a"),
        state: String = ModelAssetState.READY,
        localPath: String? = "$id.litertlm",
        variant: String = VARIANT,
        bytesDownloaded: Long = SIZE,
        updatedAt: Long = 1_700_000_000_000L,
    ): ModelAssetRecord = ModelAssetRecord(
        id = id,
        variant = variant,
        remoteUrl = "https://cdn.ake.cn/x.litertlm",
        localPath = localPath,
        expectedBytes = SIZE,
        bytesDownloaded = bytesDownloaded,
        sha256 = sha256,
        state = state,
        contextWindow = CTX,
        minRamGb = 6,
        updatedAt = updatedAt,
    )

    /** 简易 JSON 字符串字面量（夹具内部用，不走被测解析器）。 */
    private fun quote(raw: String): String = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

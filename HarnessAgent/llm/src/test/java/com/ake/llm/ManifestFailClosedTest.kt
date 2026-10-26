package com.ake.llm

import com.ake.core.common.Outcome
import com.ake.data.persist.modeldist.ManifestCanonicalForm
import com.ake.data.persist.modeldist.ManifestSchema
import com.ake.data.persist.modeldist.ManifestTrust
import com.ake.data.persist.modeldist.ManifestTrustStore
import com.ake.data.persist.modeldist.ModelDistErrorCodes
import com.ake.data.persist.modeldist.ModelEntryState
import com.ake.data.persist.modeldist.ModelManifest
import com.ake.data.persist.modeldist.ModelManifestConfig
import com.ake.data.persist.modeldist.ModelManifestEntry
import com.ake.data.persist.modeldist.ModelManifestParser
import com.ake.data.persist.modeldist.ModelManifestSource
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * ★★ **「模型分发链路」唯一的价值证明：manifest 签名不对时，客户端拒绝下载。**
 *
 * ## 为什么这个测试是那条链路的全部意义
 *
 * 客户端默认 **fail-closed**（`ModelManifestConfig.UNCONFIGURED` ⇒ 拿不到签名 manifest
 * 就不下载）。这条性质的价值 100% 取决于「验签是不是真的被执行、失败是不是真的拒绝」。
 * 把验签桩成恒真，测试照样绿，而它声称保护的东西从来没被验证过 ——
 * 那正是 `:data` 的 `ModelDistFixtures` 注释里警告的事。
 *
 * 所以本文件**不用桩**：
 *   - 密钥：运行时现生成的**真 RSA-2048 密钥对**（不是常量，不是假签名）；
 *   - 解析：[ModelManifestParser]（生产代码）；
 *   - 验签：[ManifestTrust.rsaSha256Verifier]（生产代码）；
 *   - 规范化：[ManifestCanonicalForm.render]（生产代码）；
 *   - 下载：[ModelDistributionSync.ensureCurrent]（生产入口）。
 *
 * ## 与运维侧脚本的契约
 *
 * [GOLDEN_CANONICAL] 是从 `ManifestCanonicalForm.render()` **实拍**出来的一串字节，
 * 运维侧生成器 `scripts/model_manifest.py` 的 `GOLDEN_CANONICAL` 断言同一串。
 * 两端都钉住它 ⇒ 服务端规范化与客户端规范化一旦漂移，两边各红一次，
 * 不会等到「全网用户同一时刻拉不到模型」才发现。
 *
 * ## 关于私钥
 * 本文件**不引入任何私钥文件**，密钥在测试进程内现生成、随进程消失。
 * 仓库里不存在 manifest 私钥 —— 这是硬约束，不是洁癖。
 */
@RunWith(JUnit4::class)
class ManifestFailClosedTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var downloader: ModelDownloader
    private lateinit var ledger: FakeModelLedgerGateway

    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val keyId: String = "model-ca-2026"

    /** 与 `scripts/model_manifest.py` 的 GOLDEN_CANONICAL 逐字节一致。 */
    private val goldenCanonical: String = GOLDEN_CANONICAL

    private fun setup() {
        dir = tmp.newFolder("models")
        downloader = ModelDownloader(dir)
        ledger = FakeModelLedgerGateway()
    }

    // ───────────────────────────────────────── ① 规范化字节：与运维侧对齐

    /**
     * `ManifestCanonicalForm.render()` 的**实拍值**。
     *
     * 任何一侧（Kotlin 客户端 / Python 运维脚本）改了渲染规则都会红。
     * 这不是「把实现抄一遍」—— 它是两侧之间的**唯一契约点**，
     * 而契约的内容必须由生产代码产生，不能由任一侧自己声明。
     */
    @Test
    fun `规范化字节必须与运维侧脚本的 golden 一致`() {
        val rendered = ManifestCanonicalForm.render(fixtureManifest()).toString(Charsets.UTF_8)

        assertThat(rendered).isEqualTo(goldenCanonical)
    }

    /** 规范化不含 signature 块 —— 「自己签自己」是不可能的。 */
    @Test
    fun `规范化字节不含 signature 字段`() {
        val rendered = ManifestCanonicalForm.render(fixtureManifest()).toString(Charsets.UTF_8)

        assertThat(rendered).doesNotContain("signature")
        assertThat(rendered).doesNotContain("key_id")
    }

    /** 条目顺序不该影响签名 —— 否则 CDN 重排一次数组就全网验签失败。 */
    @Test
    fun `条目顺序不影响规范化字节`() {
        val a = fixtureManifest()
        val b = a.copy(models = a.models.reversed())

        assertThat(ManifestCanonicalForm.render(a)).isEqualTo(ManifestCanonicalForm.render(b))
    }

    // ───────────────────────────────────────── ② 验签：正向必须过

    @Test
    fun `合法签名的 manifest 被接受`() {
        val raw = signedJson(fixtureManifest())

        val parsed = parser().parse(raw)

        assertThat(parsed).isInstanceOf(Outcome.Ok::class.java)
        assertThat((parsed as Outcome.Ok).value.manifestVersion).isEqualTo(7L)
    }

    // ───────────────────────────────────────── ③ 验签：负向必须拒（fail-closed）

    /** 内容被改（revision 5 → 9）⇒ 旧签名必须失效。 */
    @Test
    fun `内容被篡改的 manifest 被拒绝`() {
        val m = fixtureManifest()
        val raw = signedJson(m).replace("\"revision\":5", "\"revision\":9")

        val parsed = parser().parse(raw)

        assertThat(parsed).isInstanceOf(Outcome.Err::class.java)
        assertThat((parsed as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.SIGNATURE_INVALID)
    }

    /** 签名值坏一个字节 ⇒ 拒绝。 */
    @Test
    fun `签名值损坏的 manifest 被拒绝`() {
        val m = fixtureManifest()
        val sig = sign(m)
        val rawBytes = Base64.getDecoder().decode(sig)
        rawBytes[0] = (rawBytes[0].toInt() xor 0xFF).toByte()
        val broken = Base64.getEncoder().encodeToString(rawBytes)

        val raw = documentWithSignature(m, alg = "RSA-SHA256", keyId = keyId, value = broken)

        assertThat(parser().parse(raw)).isInstanceOf(Outcome.Err::class.java)
        assertThat((parser().parse(raw) as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.SIGNATURE_INVALID)
    }

    /** keyId 没钉过 ⇒ 连验都不验，直接拒（不得降级为「放行」）。 */
    @Test
    fun `未登记的 key_id 被拒绝`() {
        val m = fixtureManifest()
        val raw = documentWithSignature(m, "RSA-SHA256", "attacker-key", sign(m))

        val parsed = parser().parse(raw)

        assertThat(parsed).isInstanceOf(Outcome.Err::class.java)
        assertThat((parsed as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.SIGNATURE_KEY_UNKNOWN)
    }

    /** 没有 signature 块 ⇒ 拒绝。这是「运维还没产出 manifest」的默认态。 */
    @Test
    fun `没有 signature 块的 manifest 被拒绝`() {
        val m = fixtureManifest()
        val raw = documentOf(m, signature = null)

        val parsed = parser().parse(raw)

        assertThat(parsed).isInstanceOf(Outcome.Err::class.java)
        assertThat((parsed as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.SIGNATURE_MISSING)
    }

    /** alg 不是 RSA-SHA256 ⇒ 拒绝（不做「尽量验一下」）。 */
    @Test
    fun `未知算法的 signature 被拒绝`() {
        val m = fixtureManifest()
        val raw = documentWithSignature(m, "Ed25519", keyId, sign(m))

        val parsed = parser().parse(raw)

        assertThat(parsed).isInstanceOf(Outcome.Err::class.java)
        assertThat((parsed as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.SIGNATURE_ALG_UNSUPPORTED)
    }

    // ───────────────────────────────────────── ④ ★ 端到端：拒绝 ⇒ 不下载

    /**
     * **本文件的核心用例。**
     *
     * 「签名不对」必须一路传导到「一个字节都没下」：`ensureCurrent` 是 `:app`
     * 的 `ModelDownloadService` 唯一该调的入口，它返回 Err 且 `transfer` **从未被调用**，
     * 模型目录保持为空。
     *
     * 判据取「transfer 没被调用」而不是「返回了 Err」：后者在
     * 「验签其实恒真、但后面某处也失败了」的情况下也会绿 —— 那是假绿。
     */
    @Test
    fun `manifest 签名不对时 不下载任何字节`() = runBlocking {
        setup()
        val tampered = signedJson(fixtureManifest()).replace("\"revision\":5", "\"revision\":9")
        val sync = ModelDistributionSync(
            ledger,
            downloader,
            source(serving = tampered),
        )

        var httpOpened = false
        sync.transfer = { _, _ ->
            httpOpened = true
            Outcome.Ok(File(dir, "never-written"))
        }

        val outcome = sync.ensureCurrent(appVersionCode = 1)

        assertThat(outcome).isInstanceOf(Outcome.Err::class.java)
        assertThat((outcome as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.SIGNATURE_INVALID)
        // ★★ 真正的判据：一个字节都没下
        assertThat(httpOpened).isFalse()
        assertThat(dir.listFiles()).isEmpty()
        // 账本也没有被开过 —— 连「准备下载」这件事都没发生
        assertThat(ledger.log).isEmpty()
    }

    /**
     * 正向对照：签名合法 ⇒ 真的会去下载。
     *
     * ★ 它存在的理由是把上面那条从「假绿」里捞出来：如果「验签恒真」，
     *   上面那条的 Err 只能来自别处，而这里就不会 Ok —— 两条一起才构成证明。
     */
    @Test
    fun `manifest 签名合法时 才会真正下载`() = runBlocking {
        setup()
        // payload 放在 modelsDir **之外**：它不该被 `ModelDirInventory.probe` 扫到，
        // 否则「已装」的事实会混进决议，这条用例就不再是纯粹的「签名合法 ⇒ 去下载」。
        val src = tmp.newFolder("src")
        val payload = File(src, "payload.bin").apply { writeText("the-real-model-bytes") }
        val sha = sha256Of(payload)
        val entry = fixtureEntry(revision = 5).copy(sha256 = sha, sizeBytes = payload.length())
        val raw = signedJson(fixtureManifest(entry))
        val sync = ModelDistributionSync(ledger, downloader, source(serving = raw))

        var httpOpened = false
        sync.transfer = { _, _ ->
            httpOpened = true
            Outcome.Ok(payload)
        }

        val outcome = sync.ensureCurrent(appVersionCode = 1)

        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        assertThat(httpOpened).isTrue()
        assertThat((outcome as Outcome.Ok).value).isEqualTo(downloader.localFileOf("asset-1"))
    }

    /** 未配置（当前线上真实状态）⇒ 同样不下载。 */
    @Test
    fun `manifest 未配置时 不下载任何字节`() = runBlocking {
        setup()
        val sync = ModelDistributionSync(
            ledger,
            downloader,
            ModelManifestSource(
                config = ModelManifestConfig.UNCONFIGURED,
                transport = { throw AssertionError("不该联网") },
                parser = ModelManifestParser(),
            ),
        )
        var httpOpened = false
        sync.transfer = { _, _ -> httpOpened = true; Outcome.Ok(File(dir, "never")) }

        val outcome = sync.ensureCurrent(appVersionCode = 1)

        assertThat((outcome as Outcome.Err).error.code)
            .isEqualTo(ModelDistErrorCodes.MANIFEST_NOT_CONFIGURED)
        assertThat(httpOpened).isFalse()
    }

    // ───────────────────────────────────────── 辅助

    private fun parser(trust: ManifestTrustStore = trustStore()): ModelManifestParser =
        ModelManifestParser(trust)

    /** 公钥钉扎 —— 客户端就是这么认钥匙的（X.509 SubjectPublicKeyInfo DER 的 base64）。 */
    private fun trustStore(): ManifestTrustStore = ManifestTrust.pinned(
        keyId to ManifestTrust.rsaSha256Verifier(
            Base64.getEncoder().encodeToString(keyPair.public.encoded),
        ),
    )

    private fun source(serving: String): ModelManifestSource = ModelManifestSource(
        config = ModelManifestConfig(
            manifestUrl = "https://cdn.ake.cn/model-manifest.json",
            pinnedKeys = mapOf(
                keyId to Base64.getEncoder().encodeToString(keyPair.public.encoded),
            ),
        ),
        transport = { Outcome.Ok(serving) },
        parser = parser(),
        cache = null,
    )

    /** 真签名：覆盖 `ManifestCanonicalForm.render(m)` 的字节。 */
    private fun sign(m: ModelManifest): String {
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(ManifestCanonicalForm.render(m))
        }.sign()
        return Base64.getEncoder().encodeToString(sig)
    }

    private fun signedJson(m: ModelManifest): String =
        documentWithSignature(m, "RSA-SHA256", keyId, sign(m))

    private fun documentWithSignature(
        m: ModelManifest,
        alg: String,
        keyId: String,
        value: String,
    ): String = documentOf(
        m,
        signature = """{"alg":${q(alg)},"key_id":${q(keyId)},"value":${q(value)}}""",
    )

    /** 拼一份要发到 CDN 的 JSON —— 形态与 `scripts/model_manifest.py` 的输出一致。 */
    private fun documentOf(m: ModelManifest, signature: String?): String = buildString {
        append('{')
        append("\"schema_version\":").append(m.schemaVersion).append(',')
        append("\"manifest_version\":").append(m.manifestVersion).append(',')
        append("\"generated_at\":").append(m.generatedAt).append(',')
        append("\"models\":[")
        m.models.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append(entryJson(e))
        }
        append(']')
        if (signature != null) append(',').append("\"signature\":").append(signature)
        append('}')
    }

    private fun entryJson(e: ModelManifestEntry): String = buildString {
        append('{')
        append("\"model_id\":").append(q(e.modelId)).append(',')
        append("\"variant\":").append(q(e.variant)).append(',')
        append("\"revision\":").append(e.revision).append(',')
        append("\"version\":").append(q(e.version)).append(',')
        append("\"url\":").append(q(e.url)).append(',')
        append("\"size_bytes\":").append(e.sizeBytes).append(',')
        append("\"sha256\":").append(q(e.sha256)).append(',')
        append("\"min_app_version_code\":").append(e.minAppVersionCode).append(',')
        append("\"max_app_version_code\":")
            .append(e.maxAppVersionCode?.toString() ?: "null").append(',')
        append("\"context_window\":").append(e.contextWindow).append(',')
        append("\"min_ram_gb\":").append(e.minRamGb).append(',')
        append("\"capabilities\":")
            .append(e.capabilities.joinToString(",", "[", "]") { q(it) }).append(',')
        append("\"state\":").append(q(e.state.name))
        append('}')
    }

    private fun q(raw: String): String = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun fixtureManifest(entry: ModelManifestEntry = fixtureEntry()): ModelManifest =
        ModelManifest(
            schemaVersion = ManifestSchema.CURRENT,
            manifestVersion = 7L,
            generatedAt = 1_700_000_000_000L,
            models = listOf(entry),
            signature = null,
        )

    /** 与 `scripts/model_manifest.py` 的夹具逐字段一致。 */
    private fun fixtureEntry(revision: Long = 5L): ModelManifestEntry = ModelManifestEntry(
        modelId = "gemma-4-e2b-it",
        variant = "int4",
        revision = revision,
        version = "2026.10.5",
        url = "https://cdn.ake.cn/models/gemma-4-e2b-it/int4/r5.litertlm",
        sizeBytes = 2_782_923_040L,
        sha256 = "a".repeat(64),
        minAppVersionCode = 1,
        maxAppVersionCode = null,
        contextWindow = 8192,
        minRamGb = 6,
        capabilities = listOf("TEXT", "TOOLS"),
        state = ModelEntryState.ACTIVE,
    )

    private fun sha256Of(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { b -> "%02x".format(b) }

    private companion object {
        /**
         * 由 `ManifestCanonicalForm.render()` 实拍得到的规范化字节。
         * `scripts/model_manifest.py` 断言同一串 —— 两侧漂移必红。
         */
        const val GOLDEN_CANONICAL: String =
            "{\"schema_version\":1,\"manifest_version\":7,\"generated_at\":1700000000000," +
                "\"models\":[{\"model_id\":\"gemma-4-e2b-it\",\"variant\":\"int4\"," +
                "\"revision\":5,\"version\":\"2026.10.5\"," +
                "\"url\":\"https://cdn.ake.cn/models/gemma-4-e2b-it/int4/r5.litertlm\"," +
                "\"size_bytes\":2782923040," +
                "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"," +
                "\"min_app_version_code\":1,\"max_app_version_code\":null," +
                "\"context_window\":8192,\"min_ram_gb\":6," +
                "\"capabilities\":[\"TEXT\",\"TOOLS\"],\"state\":\"ACTIVE\"}]}"
    }
}

package com.ake.app.di

import android.content.Context
import android.net.Uri
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.ports.LocalManifest
import com.ake.core.ports.LocalModelCandidate
import com.ake.core.ports.LocalModelReceipt
import com.ake.core.ports.ModelAvailability
import com.ake.core.ports.ModelImportPort
import com.ake.data.persist.modeldist.ManifestCache
import com.ake.data.persist.modeldist.ModelEntryState
import com.ake.data.persist.modeldist.ModelFileImporter
import com.ake.data.persist.modeldist.ModelManifestEntry as DomainEntry
import com.ake.data.persist.modeldist.ModelManifestImporter
import com.ake.data.persist.modeldist.ModelManifestParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地模型导入的装配（`:core:ports` 的 [ModelImportPort] ← `:data` 的两个 Importer）。
 *
 * ## ★★ 为什么这个适配器必须落在 `:app`
 *
 * 它需要同时看见三样东西，而能同时看见它们的模块只有一个：
 *
 *  | 需要的东西                              | 谁能给 |
 *  |-----------------------------------------|--------|
 *  | `ModelManifestImporter` / `ModelFileImporter`  | `:data`（`:ui` 被守卫② 禁止 `import com.ake.data.*`） |
 *  | `ContentResolver`（读用户在选择器里选的 `content://`） | `:app`（`:data` 没有 Activity / URI 权限的概念） |
 *  | [ModelImportPort] 这个契约               | `:core:ports`（三者都能看见） |
 *
 * 这与 `CoreBindingsModule.bindStepTraceCodec`、`ModelDistributionModule` 里的
 * `RoomModelLedgerGateway` 是**同一个形状**：组合根是唯一能同时看见两侧的地方。
 * ⚠ 规则一律不许写在这里 —— 长度先验、摘要校验、`.part` 转正全部归 `:data`，
 *   这里多写一个 `if`，`:data` 的单测与真机行为就会分叉。
 *
 * ## ★★ 进度是怎么"变真"的
 *
 * `ModelFileImporter.importStream` 的签名只有 `(entry, opener)`，**没有进度回调**。
 * 但它的第二个参数是 `() -> InputStream` —— 于是这里在用户选中的那个流外面包一层
 * [CountingInputStream]，从这一层数出「已经读了多少字节」。
 *
 * 这不是估算：`CountingInputStream` 数的是 `read()` 真实返回的字节数，而那正是写进
 * `.part` 的字节数。所以 UI 上那条进度条的每一个百分点背后都有对应的字节。
 * （反过来：如果这里按时间插值出一个百分比，那就是在假装知道 —— 2.6 GB 的拷贝
 *  在一台热到降频的手机上能比开头慢一半，插值出来的数必然是错的。）
 */
@Module
@InstallIn(SingletonComponent::class)
public object ModelImportModule {

    @Provides
    @Singleton
    public fun provideModelImportPort(
        @ApplicationContext context: Context,
        manifestImporter: ModelManifestImporter,
        fileImporter: ModelFileImporter,
        parser: ModelManifestParser,
        cache: ManifestCache,
    ): ModelImportPort = AndroidModelImportPort(
        context = context,
        manifestImporter = manifestImporter,
        fileImporter = fileImporter,
        parser = parser,
        cache = cache,
    )
}

/**
 * 纯转发 + 三类极小的适配（读字节、数进度、查回原始条目）。**没有第四条职责。**
 */
internal class AndroidModelImportPort(
    private val context: Context,
    private val manifestImporter: ModelManifestImporter,
    private val fileImporter: ModelFileImporter,
    private val parser: ModelManifestParser,
    private val cache: ManifestCache,
) : ModelImportPort {

    /**
     * 「候选项 → `:data` 的原始条目」的回查表。
     *
     * ## 为什么需要它
     *
     * Port 交给 UI 的 [LocalModelCandidate] 是**投影**（只带展示字段），而 `:data` 的
     * `importStream` 要的是完整的 `ModelManifestEntry`。两者之间必须能还原回去。
     *
     * ## ★ 为什么只在 [importManifest] 时填
     *
     * 这是唯一一次我们真的拿着完整条目的时机。之后 [importModelFile] 先查这张表；
     * 查不到（进程被杀导致这张内存表没了，而用户正停在第二步）就退到第 2 步：
     * 重读 `ManifestCache` —— `ModelManifestImporter` 侧载成功时已经把原文写进去了，
     * 于是**只要 manifest 确实通过过一次，第二步就不依赖于「内存还在」**。
     * 这两级都 miss 才报错，且报的是"请重新选一次 manifest"这种用户做得到的动作。
     */
    private val known = ConcurrentHashMap<String, DomainEntry>()

    override suspend fun importManifest(sourceUri: String): Outcome<LocalManifest> = withContext(Dispatchers.IO) {
        val raw = readText(sourceUri)
            ?: return@withContext Outcome.Err(sourceMissing())

        when (val imported = manifestImporter.import(raw)) {
            is Outcome.Err -> Outcome.Err(imported.error)
            is Outcome.Ok -> {
                val manifest = imported.value
                manifest.models.forEach { entry -> known[keyOf(entry)] = entry }
                Outcome.Ok(
                    LocalManifest(
                        manifestVersion = manifest.manifestVersion,
                        candidates = manifest.models.map { it.toCandidate() },
                    ),
                )
            }
        }
    }

    override suspend fun importModelFile(
        candidate: LocalModelCandidate,
        sourceUri: String,
        onProgress: (copiedBytes: Long) -> Unit,
    ): Outcome<LocalModelReceipt> = withContext(Dispatchers.IO) {
        val entry = resolve(candidate)
            ?: return@withContext Outcome.Err(
                AppError(
                    "MODEL_IMPORT_SOURCE_MISSING",
                    "err_model_import_source_missing",
                    null,
                    Severity.MEDIUM,
                ),
            )

        // ★ opener 里抛异常是被期待的一条路径：`ModelFileImporter` 会把它收成 IO_FAILED，
        //   并且先把 `.part` 删干净 —— 所以这里不需要自己管半成品。
        val opener: () -> InputStream = {
            val stream = context.contentResolver.openInputStream(Uri.parse(sourceUri))
                ?: throw FileNotFoundException(sourceUri)
            CountingInputStream(stream, onProgress)
        }

        when (val r = fileImporter.importStream(entry, opener)) {
            is Outcome.Err -> Outcome.Err(r.error)
            is Outcome.Ok -> Outcome.Ok(r.value.toReceipt())
        }
    }

    override suspend fun alreadyImported(candidate: LocalModelCandidate): Boolean = withContext(Dispatchers.IO) {
        resolve(candidate)?.let { fileImporter.isPresent(it) } ?: false
    }

    // ──────────────────────────────────────────────── 内部

    /** 候选 → 原始条目：先查内存，退回去重读磁盘缓存。理由见 [known]。 */
    private fun resolve(candidate: LocalModelCandidate): DomainEntry? {
        known[keyOf(candidate)]?.let { return it }
        val cached = cache.read() ?: return null
        val manifest = (parser.parse(cached) as? Outcome.Ok)?.value ?: return null
        val hit = manifest.models.firstOrNull { keyOf(it) == keyOf(candidate) } ?: return null
        known[keyOf(hit)] = hit
        return hit
    }

    private fun readText(uri: String): String? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        null
    }

    private fun keyOf(entry: DomainEntry): String = "${entry.modelId}|${entry.variant}|${entry.revision}"

    private fun keyOf(candidate: LocalModelCandidate): String =
        "${candidate.modelId}|${candidate.variant}|${candidate.revision}"

    private fun sourceMissing(): AppError = AppError(
        "MODEL_IMPORT_SOURCE_MISSING",
        "err_model_import_source_missing",
        null,
        Severity.MEDIUM,
    )
}

/**
 * 在源流外面数已读字节，用来给 `:data` 那个没有进度回调的拷贝做真实进度。
 *
 * ## 为什么不由 `:data` 自己在 while 循环里数
 *
 * `importStream` 的签名是既定的（`entry` + `opener`），给它加一个回调参数等于为了
 * 让 UI 能显示而改数据层的契约。这里反过来：**显示需求自己数** —— 在 `opener()`
 * 被调用时，把交还给 `:data` 的那个流换成本类的实例即可，`:data` 一行都不用改。
 *
 * ## ★ 数的到底是什么
 *
 * `read()` 的返回值，也就是**上游真正读到的字节数**。它不是估算也不是插值，误差为零。
 * 唯一要注意的是 `mark` / `reset` 会让同一段被重复计入，而 `importStream` 只用带
 * 1 MiB 缓冲的顺序读、不回溯，所以这里不处理。
 */
private class CountingInputStream(
    private val source: InputStream,
    private val onRead: (Long) -> Unit,
) : InputStream() {

    private var total: Long = 0L

    override fun read(): Int = source.read().also { n -> if (n >= 0) bump(1L) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        source.read(b, off, len).also { n -> if (n > 0) bump(n.toLong()) }

    override fun skip(n: Long): Long = source.skip(n).also { skipped -> if (skipped > 0) bump(skipped) }

    override fun available(): Int = source.available()

    override fun markSupported(): Boolean = source.markSupported()

    override fun close(): Unit = source.close()

    @Synchronized
    private fun bump(n: Long) {
        total += n
        onRead(total)
    }
}

private fun DomainEntry.toCandidate(): LocalModelCandidate = LocalModelCandidate(
    modelId = modelId,
    variant = variant,
    revision = revision,
    version = version,
    sizeBytes = sizeBytes,
    contextWindow = contextWindow,
    minRamGb = minRamGb,
    availability = when (state) {
        ModelEntryState.ACTIVE -> ModelAvailability.ACTIVE
        ModelEntryState.DEPRECATED -> ModelAvailability.DEPRECATED
        ModelEntryState.REVOKED -> ModelAvailability.REVOKED
    },
)

private fun ModelFileImporter.Receipt.toReceipt(): LocalModelReceipt = LocalModelReceipt(
    fileName = fileName,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    modelId = modelId,
    variant = variant,
    copied = copied,
)

package com.ake.ui.spi

import android.content.Context
import com.ake.core.common.Outcome
import com.ake.core.modelapi.ModelPort
import com.ake.core.ports.ModelLifecyclePort
import com.ake.data.persist.wipe.WipeEverythingUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「一键清除全部痕迹」的 UI 侧门面。
 *
 * ## 为什么接口在 `:ui` 而实现在 `:data`
 *
 * 清除这件事的**执行**属于 `:data`（它要关库、停在途写入队列、删外置轨迹目录），
 * 而「清完之后告诉用户什么」属于 `:ui`（`StorageUsage` 分项、`WipeReport` 措辞）。
 * 所以 [AndroidPrivacyPort] 现在是一层薄门面：**注入 `WipeEverythingUseCase` 承担全部删除动作**，
 * 自己只保留三件 `:data` 不该知道的事 —— 先断 MCP 连接、先卸载模型、以及把结果翻成人话。
 *
 * （此前这里按 Context + Keystore 自己重写了一份删除逻辑，与 `WipeEverythingUseCase` 重复。
 *  两份实现最危险的不是冗余，而是**范围会悄悄分叉**：这边补了 `app_webview/` 而那边没补，
 *  于是「一键清除」到底清没清干净取决于谁来回答。现在只有一份。）
 *
 * ## ★ 清除范围（由 `WipeEverythingUseCase` 保证，`AndroidPrivacyPort` 只做核验）
 *
 * ```
 *  ① Keystore 密钥粉碎   ← 主手段：删密钥后 R1 密文数学上不可恢复
 *  ② 停在途写入队列
 *  ③ Room 库文件（-wal / -shm）
 *  ④ 外置轨迹目录
 *  ⑤ DataStore + filesDir/mcp + filesDir/settings
 *  ⑥ ★ app_webview/      ← 我们以登录态访问网页，Cookie / LocalStorage / IndexedDB 全在这里
 *  ⑦ 模型文件（可选）
 * ```
 *
 * 第 ⑥ 步是最不可省的一步：只删自己的数据库而留着它，「一键清除痕迹」是假的 ——
 * 拿 ADB 看一眼 `dataDir/app_webview/` 就能还原访问过哪些站点。
 */
public interface PrivacyPort {

    /** 存储占用（字节）。按用途分项，因为用户真正能删的只有其中几项。 */
    public suspend fun usage(): StorageUsage

    /** 执行清除。@param deleteModelFiles 是否连 2.6 GB 的模型文件一起删。 */
    public suspend fun wipe(deleteModelFiles: Boolean): WipeReport

    /** 立即断开所有外部工具连接（清除的前置动作，也是独立的隐私开关）。 */
    public suspend fun disconnectTools()
}

public data class StorageUsage(
    val totalBytes: Long,
    val databaseBytes: Long,
    val traceBytes: Long,
    val webViewBytes: Long,
    val modelBytes: Long,
    val availableBytes: Long,
)

public data class WipeReport(
    val keystoreEntriesDeleted: Int,
    val databasesDeleted: Int,
    val webViewCleared: Boolean,
    val modelFilesDeleted: Boolean,
    val failures: List<String>,
)

/**
 * `PrivacyPort` 的 Android 实现 —— **薄门面**。
 *
 * 删除动作全部委托给注入的 [WipeEverythingUseCase]（`@Singleton`，由 `:data` 的
 * `PersistenceModule` 提供）。这里只做三件它不该知道的事：
 *
 *  1. **先断 MCP 连接** —— 否则清完可能立刻又被外部工具写回来；
 *  2. **先卸载模型** —— 释放 2.6 GB 常驻内存，同时让它停止往缓存写；
 *  3. **把结果翻成人话** —— `WipeEverythingUseCase.Result` 只有一个 `modelsDeleted`，
 *     而设置页要的是「删了几个库、WebView 清干净没、有没有失败的项」。
 *     所以这里在委托**前后各快照一次** Keystore 别名数与数据库文件数，用差值如实汇报 ——
 *     是核验，不是重新实现一遍删除。
 *
 * 刻意在 `Dispatchers.IO` 上跑：删几百个轨迹小文件是同步 IO，
 * 放到主线程会直接 ANR —— 而「点了一键清除之后卡死」是最伤信任的一种故障。
 *
 * ## ⚠ 一个已知的诚实性缺口（已反馈给 `:data`）
 *
 * `WipeEverythingUseCase` 内部每一步都是 `runCatching { }.onFailure { log }`，**失败不外抛**，
 * 所以 `failures` 里只可能有「整个用例抛异常」这一种。上面用差值兜住了
 * 「密钥没删掉 / 库没删掉」这两种最严重的静默失败，但更细的项仍需
 * `WipeEverythingUseCase.Result` 自己汇总 `failures` 才能如实告知用户。
 */
@Singleton
public class AndroidPrivacyPort @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val wipeEverything: WipeEverythingUseCase,
    private val modelLifecycle: ModelLifecyclePort,
    private val modelPort: ModelPort,
    private val toolShutdown: ToolShutdownPort,
) : PrivacyPort {

    override suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val db = ctx.databaseList().orEmpty().sumOf { name -> sizeOf(ctx.getDatabasePath(name)) } +
            ctx.databaseList().orEmpty().sumOf { name ->
                sizeOf(File(ctx.getDatabasePath(name).parentFile, "$name-wal")) +
                    sizeOf(File(ctx.getDatabasePath(name).parentFile, "$name-shm"))
            }
        StorageUsage(
            totalBytes = db + sizeOf(File(ctx.filesDir, TRACE_DIR)) +
                sizeOf(File(ctx.dataDir, WEBVIEW_DIR)) + sizeOf(File(ctx.filesDir, MODELS_DIR)),
            databaseBytes = db,
            traceBytes = sizeOf(File(ctx.filesDir, TRACE_DIR)),
            webViewBytes = sizeOf(File(ctx.dataDir, WEBVIEW_DIR)),
            modelBytes = sizeOf(File(ctx.filesDir, MODELS_DIR)),
            availableBytes = runCatching {
                val stat = android.os.StatFs(ctx.filesDir.absolutePath)
                stat.availableBlocksLong * stat.blockSizeLong
            }.getOrDefault(0L),
        )
    }

    override suspend fun wipe(deleteModelFiles: Boolean): WipeReport = withContext(Dispatchers.IO) {
        val failures = ArrayList<String>()

        // ⓪ 先断外部连接 / 卸载模型 —— 否则清完可能立刻又被写回来
        runCatching { disconnectTools() }.onFailure { failures += "断开工具连接失败：${it.message}" }
        runCatching { modelPort.unload() }.onFailure { failures += "模型卸载失败：${it.message}" }

        // ① 模型资产：必须在删库**之前**做，否则它回头去写已经被删掉的库
        var modelDeleted = false
        if (deleteModelFiles) {
            runCatching { modelLifecycle.deleteModelFile(MODEL_ID) }
                .onFailure { failures += "模型文件删除失败：${it.message}" }
                .onSuccess { o -> if (o is Outcome.Ok) modelDeleted = true }
        }

        // ② 真正的清除：密钥 → 库 → 轨迹 → 配置 → app_webview → 模型目录，全在 `:data`
        val keysBefore = keystoreAliases().size
        val dbsBefore = ctx.databaseList().orEmpty().size

        val result = runCatching { wipeEverything(deleteModelFiles) }
            .onFailure { failures += "清除失败：${it.message}" }
            .getOrNull()
        if (result?.modelsDeleted == true) modelDeleted = true

        // ③ 核验。差值而不是「假定成功」：清没清干净要用文件系统回答，不能用返回值假装
        WipeReport(
            keystoreEntriesDeleted = (keysBefore - keystoreAliases().size).coerceAtLeast(0),
            databasesDeleted = (dbsBefore - ctx.databaseList().orEmpty().size).coerceAtLeast(0),
            // ★ 判据是目录。Cookie / LocalStorage / IndexedDB 的落盘都在它里面，
            //   目录没了就是没了；而 `CookieManager.hasCookies()` 在没有 WebView 实例时
            //   结果不可靠，拿它做判据只会给出假失败。
            webViewCleared = !File(ctx.dataDir, WEBVIEW_DIR).exists(),
            modelFilesDeleted = modelDeleted,
            failures = failures,
        )
    }

    override suspend fun disconnectTools() {
        runCatching { toolShutdown.shutdown() }
    }

    /** 本 App 名下的 Keystore 别名。只数不删 —— 删除是 `WipeEverythingUseCase` 的职责。 */
    private fun keystoreAliases(): List<String> = runCatching {
        KeyStore.getInstance(KEYSTORE)
            .apply { load(null) }
            .aliases()
            .toList()
            .filter { it.startsWith(KEY_ALIAS_PREFIX) }
    }.getOrDefault(emptyList())

    private fun sizeOf(f: File?): Long = runCatching {
        if (f == null || !f.exists()) 0L
        else if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else f.length()
    }.getOrDefault(0L)

    private companion object {
        const val TRACE_DIR = "trace"
        const val WEBVIEW_DIR = "app_webview"
        const val MODELS_DIR = "models"
        const val KEY_ALIAS_PREFIX = "ake."
        const val KEYSTORE = "AndroidKeyStore"
        const val MODEL_ID = "gemma-4-E2B-it"
    }
}

/**
 * 断开外部工具连接的能力。
 *
 * 单独抽成一个接口的原因是**：`McpToolFacade` 在 `:mcp`，而 `:mcp` 是实现细节**。
 * `PrivacyPort` 只认识「把外部连接断掉」这件事，不认识 MCP —— 这样换掉整个 MCP 实现时，
 * 隐私这条路径一行都不用改。绑定在 `:app`（唯一能同时看见 `:ui` 与 `:mcp` 的组合根）。
 */
public interface ToolShutdownPort {
    public suspend fun shutdown()
}

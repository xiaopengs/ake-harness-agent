package com.ake.ui.spi

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.ake.core.modelapi.ModelPort
import com.ake.core.ports.ModelLifecyclePort
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
 * ## 为什么这个接口定义在 `:ui` 而不是 `core:ports`
 *
 * 它**本该**在 `core:ports`（L2），由 `:data` 实现 —— `:data` 已经有一个写得很扎实的
 * `WipeEverythingUseCase`。但那个用例目前没有被 Hilt 提供，且它的依赖（`AkeDatabase`、
 * `TraceBufferWriter`、`PayloadStore`、`MemoryStoreImpl`）全是 `:data` 内部类型，
 * UI 一旦 import 就把 Room 拖进了 L6。
 *
 * 所以这里给出门面 + 一个**只用 Android 框架 + core 契约**的实现，
 * 在 `:data` 提供 `PrivacyPort` 绑定后整体替换即可（调用方一行不用改）。
 *
 * ## ★ 清除范围必须包含 `app_webview/`
 *
 * 我们以用户的**登录态**访问网页：Cookie / LocalStorage / IndexedDB / FormData 全在
 * `dataDir/app_webview/` 里。只删自己的数据库而留着它，「一键清除痕迹」是假的 ——
 * 拿 ADB 看一眼这个目录就能还原访问过哪些站点。这一条是本实现里最不可省的一步。
 *
 * ## 顺序不能反
 *
 * **先删密钥，再删文件。** 反过来的失败方向是灾难性的：中途崩溃会留下「文件还在、
 * 密钥已没」的永久垃圾；而「密钥先没、文件还在」只是等一次覆盖 —— 数据已经不可读。
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
 * 基于 Android 框架的实现。
 *
 * 刻意在 `Dispatchers.IO` 上跑：删几百个轨迹小文件是同步 IO，
 * 放到主线程会直接 ANR —— 而「点了一键清除之后卡死」是最伤信任的一种故障。
 */
@Singleton
public class AndroidPrivacyPort @Inject constructor(
    @ApplicationContext private val ctx: Context,
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

        // ⓪ 先断外部连接，否则清完可能立刻又被写回来
        runCatching { disconnectTools() }.onFailure { failures += "断开工具连接失败：${it.message}" }

        // ① 密码学粉碎：主手段
        var keys = 0
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (alias in ks.aliases().toList()) {
                if (!alias.startsWith(KEY_ALIAS_PREFIX)) continue
                ks.deleteEntry(alias)
                keys++
            }
        }.onFailure { failures += "密钥删除失败：${it.message}" }

        // ② 卸载模型（释放 2.6 GB 常驻，同时让它不再往缓存里写）
        runCatching { modelPort.unload() }.onFailure { failures += "模型卸载失败：${it.message}" }

        // ③ 数据库及其 -wal / -shm
        var dbs = 0
        runCatching {
            for (name in ctx.databaseList().orEmpty()) {
                val f = ctx.getDatabasePath(name)
                File(f.parentFile, "$name-wal").delete()
                File(f.parentFile, "$name-shm").delete()
                if (ctx.deleteDatabase(name)) dbs++
            }
        }.onFailure { failures += "数据库删除失败：${it.message}" }

        // ④ 外置轨迹目录
        runCatching { File(ctx.filesDir, TRACE_DIR).deleteRecursively() }
            .onFailure { failures += "轨迹目录删除失败：${it.message}" }

        // ⑤ 配置（DataStore / 我这边暂存的 MCP 服务器清单）
        runCatching {
            File(ctx.filesDir, "datastore").deleteRecursively()
            File(ctx.filesDir, "mcp").deleteRecursively()
        }.onFailure { failures += "配置删除失败：${it.message}" }

        // ⑥ ★ WebView 痕迹 —— 本 App 最大一块不受我们控制的痕迹
        var webOk = true
        runCatching {
            WebView(ctx).apply {
                clearHistory(); clearCache(true); clearFormData()
            }.destroy()
            CookieManager.getInstance().apply {
                removeAllCookies { }
                flush()
            }
            WebStorage.getInstance().deleteAllData()
            // 目录本身：上面几个 API 各自只管自己那一块，目录要单独删
            File(ctx.dataDir, WEBVIEW_DIR).deleteRecursively()
        }.onFailure {
            webOk = false
            failures += "WebView 痕迹清除失败：${it.message}"
        }

        // ⑦ 模型文件：删不删由用户单独决定（2.6 GB，重下代价高）
        var modelDeleted = false
        if (deleteModelFiles) {
            runCatching {
                modelLifecycle.deleteModelFile(MODEL_ID)
                File(ctx.filesDir, MODELS_DIR).deleteRecursively()
                modelDeleted = true
            }.onFailure { failures += "模型文件删除失败：${it.message}" }
        }

        WipeReport(
            keystoreEntriesDeleted = keys,
            databasesDeleted = dbs,
            webViewCleared = webOk,
            modelFilesDeleted = modelDeleted,
            failures = failures,
        )
    }

    override suspend fun disconnectTools() {
        runCatching { toolShutdown.shutdown() }
    }

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

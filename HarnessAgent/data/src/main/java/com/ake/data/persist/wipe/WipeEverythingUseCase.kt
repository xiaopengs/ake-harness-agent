package com.ake.data.persist.wipe

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.ake.core.common.AkeLog
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.payload.PayloadStore
import com.ake.data.persist.store.MemoryStoreImpl
import com.ake.data.persist.write.TraceBufferWriter
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「一键清除全部痕迹」（数据库 §8.5 / F8）。
 *
 * 架构文档完全没有为「销毁」设计任何落点，而端侧 Agent 恰恰是最需要它的 App ——
 * 我们在用户的登录态下访问站点，并把过程完整录了下来。
 *
 * ## ★ 顺序不能改
 *
 * ```
 * ① 删 Keystore 密钥    ← 主手段，先做（见下）
 * ② 停在途写入          ← 否则刚删完又被写回来
 * ③ 删 Room 库文件      ← 含 -wal / -shm
 * ④ 删外置轨迹目录
 * ⑤ 删 DataStore 文件
 * ⑥ ★ 删 WebView 痕迹   ← 本 App 最大一块不受我们控制的痕迹
 * ⑦ 模型文件删不删由用户单独决定
 * ```
 *
 * ## 为什么「先删密钥」而不是「先删文件」
 *
 * 反过来做，如果中途崩了，**文件还在而密钥没了** ⇒ 数据变成永久垃圾，目的未达成；
 * 而**密钥先没、文件还在** ⇒ 数据已经不可读，目的达成，剩下的只是等待被覆盖。
 * **先删密钥是更安全的失败方向。**
 *
 * ## 密码学粉碎才是主手段
 *
 * 闪存的 wear leveling 让「安全擦除文件」在 Android 上根本不可靠 ——
 * 你以为覆盖了，控制器可能写到了另一个物理块。**别自欺欺人。**
 * 删除 Keystore 条目后，所有 R1 密文（goal / answer / 工具入出参 / 快照文件 /
 * scratchpad / memory content）在数学上永久不可恢复 ——
 * 这是唯一「无论文件是否被真正擦除都成立」的保证。
 *
 * ## 用法
 *
 * 由 `:data` 的 `PersistenceModule` 提供单例（Hilt `@Provides`），**不要自己 new** ——
 * 它的 `Context` 必须是 `@ApplicationContext`（`deleteDatabase` 用的是应用默认库路径），
 * 且 `TraceBufferWriter` 必须是同一个单例，否则「停在途写入」停的是另一个队列。
 */
class WipeEverythingUseCase(
    private val context: Context,
    private val db: AkeDatabase,
    private val writer: TraceBufferWriter,
    private val payloads: PayloadStore,
    private val memory: MemoryStoreImpl,
    private val keystoreAlias: String = KEY_ALIAS,
) {

    /** @param deleteModelFiles false = 保留模型（2.58 GB，重下代价高，默认由用户决定）。 */
    suspend operator fun invoke(deleteModelFiles: Boolean = false): Result = withContext(Dispatchers.IO) {
        AkeLog.w(TAG, "wipeEverything started")

        // ① 密码学粉碎
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(keystoreAlias) }
        }.onFailure { AkeLog.w(TAG, "keystore delete failed: ${it.message}") }

        // ② 停在途写入
        writer.stopAndDropQueued()

        // ③ 删 Room 库文件（含 -wal / -shm）
        runCatching {
            db.close()
            context.deleteDatabase(AkeDatabase.DB_FILE_NAME)
            dbFile()?.parentFile?.listFiles { f -> f.name.startsWith(AkeDatabase.DB_FILE_NAME) }
                ?.forEach { it.delete() }
        }.onFailure { AkeLog.w(TAG, "db delete failed: ${it.message}") }

        // ④ 外置轨迹目录
        runCatching { payloads.purgeAll() }

        // ⑤ DataStore 文件（settings / domain_policy / mcp_servers）
        runCatching {
            File(context.filesDir, "datastore").deleteRecursively()
        }.onFailure { AkeLog.w(TAG, "datastore delete failed: ${it.message}") }

        // ⑤b ★ 过渡实现：MCP 服务器清单与 UI 设置项目前由 `:ui` 暂存在 filesDir/mcp、
        //     filesDir/settings（org.json），因为 `core/ports` 还没有对应的 Store Port。
        //     本类的清除范围必须与 `AndroidPrivacyPort` 一致，否则换成我这份实现后
        //     会留下一批孤儿文件 —— 那比「有两份实现」更糟。
        //     TODO(ownership)：`core/ports` 落地 McpServerStorePort / SettingsStorePort 后，
        //     这里改成 `store.clearAll()`，本段删除。
        runCatching {
            File(context.filesDir, "mcp").deleteRecursively()
            File(context.filesDir, "settings").deleteRecursively()
        }.onFailure { AkeLog.w(TAG, "transition files delete failed: ${it.message}") }

        // ⑥ ★ WebView 痕迹 —— 架构文档完全没提的那一块
        runCatching {
            WebView(context).apply {
                clearHistory()
                clearCache(true)
                clearFormData()
            }.destroy()
            CookieManager.getInstance().apply {
                removeAllCookies { }
                flush()
            }
            WebStorage.getInstance().deleteAllData()
            File(context.dataDir, "app_webview").deleteRecursively()
        }.onFailure { AkeLog.w(TAG, "webview wipe failed: ${it.message}") }

        // ⑦ 模型文件
        if (deleteModelFiles) {
            runCatching { File(context.filesDir, "models").deleteRecursively() }
                .onFailure { AkeLog.w(TAG, "model delete failed: ${it.message}") }
        }

        // 记忆表（库文件已删，这里只是把内存态清干净，防止残留引用）
        runCatching { memory.deleteAll() }

        AkeLog.w(TAG, "wipeEverything done (modelsDeleted=$deleteModelFiles)")
        Result(modelsDeleted = deleteModelFiles)
    }

    private fun dbFile(): File? = runCatching { context.getDatabasePath(AkeDatabase.DB_FILE_NAME) }.getOrNull()

    data class Result(val modelsDeleted: Boolean)

    companion object {
        const val TAG: String = "WipeEverything"

        /** 必须与 `:app` 的 `AndroidKeystoreFieldCipher.ALIAS` 一致。 */
        const val KEY_ALIAS: String = "ake.trace.dek.v1"
        const val KEYSTORE: String = "AndroidKeyStore"

        /**
         * 一个必须一并处理、但极易被漏掉的点：
         * `android:allowBackup="false"` + Android 12+ `dataExtractionRules`。
         * 否则「云备份 / 换机迁移」会把本应不出机的数据带出去 —— 那是合规红线，不是优化项。
         * 已由 `:app` 的 Manifest 处理（本类只做一次运行期确认日志）。
         */
        fun assertBackupDisabled() {
            // 只在 logcat 留一条提示：Manifest 是构建期产物，运行期没有可靠的读取口，
            // 与其写一段脆弱的反射检查，不如把这条检查放进构建期的 lint / CI。
            AkeLog.i(TAG, "verify android:allowBackup=\"false\" + dataExtractionRules in Manifest (CI check)")
        }
    }
}

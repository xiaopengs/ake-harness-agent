package com.ake.data.persist.wipe

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.ake.core.common.AkeLog
import com.ake.core.ports.McpServerStorePort
import com.ake.core.ports.SettingsStorePort
import com.ake.core.ports.WipePort
import com.ake.core.ports.WipeResult
import com.ake.data.persist.agentasset.AgentAssetStore
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.payload.PayloadStore
import com.ake.data.persist.store.MemoryStoreImpl
import com.ake.data.persist.write.ChatMessageWriter
import com.ake.data.persist.write.TaskStateWriter
import com.ake.data.persist.write.TraceBufferWriter
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「一键清除全部痕迹」（数据库 §8.5 / F8）—— **同时是 `core:ports` 的 [WipePort] 实现**。
 *
 * 架构文档完全没有为「销毁」设计任何落点，而端侧 Agent 恰恰是最需要它的 App ——
 * 我们在用户的登录态下访问站点，并把过程完整录了下来。
 *
 * ## ★ 顺序不能改
 *
 * ```
 * ① 删 Keystore 密钥     ← 主手段，先做（见下）
 * ② 停在途写入           ← 否则刚删完又被写回来
 * ②b 两个 Store clearAll ← ★ 配置落 Room 之后，它们回到了 ② 的管辖范围
 * ③ 删 Room 库文件       ← 含 -wal / -shm
 * ④ 删外置轨迹目录
 * ④b ★★ 删聊天外置目录  ← v3：消息 payload + checkpoint envelope 共用 `filesDir/chat/`
 * ④c ★★ 删人格资产目录  ← v3：`filesDir/agents/`（头像等）
 * ⑤ 删 DataStore 文件     （历史兼容：本工程目前无 DataStore 实例）
 * ⑤b 删 :ui 过渡实现的遗留目录（窗口期兼容，见 ②b 注释）
 * ⑥ ★ 删 WebView 痕迹    ← 本 App 最大一块不受我们控制的痕迹
 * ⑦ 模型文件删不删由用户单独决定
 * ```
 *
 * ## ★ v3 为什么必须新增 ④b / ④c（上游 §7.2 提示 6）
 *
 * 「一键清除」漏掉人格与聊天记录，**比不做这个功能更糟**：
 * 用户按了「清除全部痕迹」，而最私密的那部分（`persona_charter_enc`、
 * `chat_message.content_enc`、头像）还躺在磁盘上 —— 那是一次**对信任的透支**。
 *
 * ★ 两段都放在 ④ 之后、⑤ 之前：与 v2 的顺序原则一致 —— ③ 删库（指针）→ ④ 删文件（本体）；
 * 而它们是**当前版本的正式数据**，⑤ 是历史兼容（历史兼容失败可以容忍，
 * 正式数据失败必须进 `failures` 让用户看见）。
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
 * ## ★ `failures` 不是装饰（2026-10）
 *
 * `core/ports/Privacy.kt` 的契约注释点名了这里原来的诚实性缺口：
 * 每一步都是 `runCatching { }.onFailure { log }`，失败不外抛，
 * 于是调用方只能看到「整个用例抛异常」这一种失败 —— 而那**永远不可能是主要失败形态**。
 * 真正的失败形态是「密钥删了、库没删掉」，而它原本只在 logcat 里留一行。
 *
 * 现在每一步的失败都进 [WipeResult.failures]，UI 侧（`AndroidPrivacyPort`）
 * 可以把「库没删掉」如实告诉用户，而不是拿返回值假装成功。
 *
 * ## 用法
 *
 * 由 `:data` 的 `PersistenceModule` 提供单例（Hilt `@Provides`），**不要自己 new** ——
 * 它的 `Context` 必须是 `@ApplicationContext`（`deleteDatabase` 用的是应用默认库路径），
 * 且 `TraceBufferWriter` / `TaskStateWriter` 必须是同一个单例，否则「停在途写入」停的是另一个队列。
 */
public class WipeEverythingUseCase(
    private val context: Context,
    private val db: AkeDatabase,
    private val writer: TraceBufferWriter,
    private val state: TaskStateWriter,
    private val payloads: PayloadStore,
    private val memory: MemoryStoreImpl,
    private val mcpServers: McpServerStorePort,
    private val settings: SettingsStorePort,
    /** ★ v3：聊天快照泳道（`ChatMessageWriter`）。**必须是同一个单例**，否则停错泳道。 */
    private val chatWriter: ChatMessageWriter,
    /** ★ v3：`filesDir/chat/`（消息 payload + envelope）。 */
    private val chatPayloads: PayloadStore,
    /** ★ v3：`filesDir/agents/`（头像等人格资产）。 */
    private val agentAssets: AgentAssetStore,
    private val keystoreAlias: String = KEY_ALIAS,
) : WipePort {

    /**
     * `core:ports` 的契约入口，也是**唯一**入口。
     *
     * （2026-10 清理：此前另有一个 `operator fun invoke(...)` 兼容垫片，
     *   供 `:ui` 直接注入本具体类时使用。苏映棠已把 `AndroidPrivacyPort`
     *   改为注入 `core:ports` 的 [WipePort]，该垫片连同其 `Result` 类一并删除。）
     */
    override suspend fun wipeEverything(deleteModelFiles: Boolean): WipeResult = withContext(Dispatchers.IO) {
        AkeLog.w(TAG, "wipeEverything started")
        val failures = ArrayList<String>()

        // ① 密码学粉碎
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null); deleteEntry(keystoreAlias) }
        }.onFailure { failures += step("keystore", it) }

        // ② 停在途写入。★ 三条泳道都要停 —— 只停轨迹那一条的话，
        //    状态泳道里的 `TaskFinish` 会在删完库之后把行重新写回来（看着像「清不掉」的灵异现象）；
        //    ★ v3：聊天快照泳道（[chatWriter]）同理 —— 它是**同一个库**的第三位写者。
        runCatching {
            state.stopAndDropQueued()
            writer.stopAndDropQueued()
            chatWriter.stopAndDropQueued()
        }.onFailure { failures += step("stop writers", it) }

        // ②b ★ 两个 Store 的 clearAll() —— `core/ports` 里那两条 TODO 等的就是这里。
        //     之前这一段是「删 filesDir/mcp 与 filesDir/settings 两个目录」，
        //     因为那时清单与设置由 `:ui` 用 org.json 暂存在文件里，而清除流程只能去猜路径。
        //     它们现在落在 Room，于是「清空」这件事回到 Store 自己的语义：
        //     **由 Store 决定它的数据在哪、怎么清**，而不是由清除流程记一份文件路径清单 ——
        //     后者每加一个 Store 就要记得同步一次，是最容易漏的那种约定。
        //     ★ 位置在 ② 之后、③ 之前：既不会与在途写入竞争，
        //       又在删库之前把「配置已清空」落实一遍（删库失败时至少配置已经清了）。
        runCatching { mcpServers.clearAll() }.onFailure { failures += step("mcp_servers.clearAll", it) }
        runCatching { settings.clearAll() }.onFailure { failures += step("settings.clearAll", it) }

        // ③ 删 Room 库文件（含 -wal / -shm）
        runCatching {
            db.close()
            context.deleteDatabase(AkeDatabase.DB_FILE_NAME)
            dbFile()?.parentFile?.listFiles { f -> f.name.startsWith(AkeDatabase.DB_FILE_NAME) }
                ?.forEach { it.delete() }
        }.onFailure { failures += step("db", it) }

        // ④ 外置轨迹目录
        runCatching { payloads.purgeAll() }.onFailure { failures += step("trace payloads", it) }

        // ④b ★★ 聊天外置目录 —— 消息 payload 与 checkpoint envelope **共用同一个根**
        //      ⇒ 一次 purgeAll 全覆盖（这就是共用根的全部收益：新增一类落盘物时，
        //         清除清单不需要跟着改）。顺序仍在 ③ 之后：先没了 DB 指针，再删文件。
        runCatching { chatPayloads.purgeAll() }.onFailure { failures += step("chat payloads", it) }

        // ④c ★★ 人格资产（头像）。与 ④b 同理：一个根覆盖全部人格资产。
        //     ★ identicon 头像没有文件 ⇒ 由 `avatar_kind` 列区分，不在删除范围内（§7.4）。
        runCatching { agentAssets.purgeAll() }.onFailure { failures += step("agent assets", it) }

        // ⑤ DataStore 文件（settings / domain_policy / mcp_servers）
        //    ★ 本工程目前**没有任何 DataStore 实例**（配置已落 Room，见 ConfigEntities 的类注释），
        //      这一段是纯历史兼容：它删的是一个可能由旧版本留下的目录，删不到也不算失败。
        runCatching {
            File(context.filesDir, "datastore").deleteRecursively()
        }.onFailure { AkeLog.w(TAG, "datastore delete failed (legacy, ignored): ${it.message}") }

        // ⑤b ★ 窗口期兼容：`:ui` 的过渡实现（`McpServerStore` / `UiLocalSettings`）把数据写在
        //     filesDir/mcp 与 filesDir/settings。`:data` 的真实现落地后那两条 `@Binds` 要删掉，
        //     但**「删 `@Binds`」与「发新包」之间存在窗口期**：窗口期内真机上可能既有 Room 表
        //     又有这两个目录。清除范围必须同时覆盖两种落点 ——
        //     留下一批孤儿文件，比「有两份实现」更糟，因为它会让「一键清除」这件事变成假的。
        runCatching {
            File(context.filesDir, "mcp").deleteRecursively()
            File(context.filesDir, "settings").deleteRecursively()
        }.onFailure { failures += step("legacy ui files", it) }

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
        }.onFailure { failures += step("webview", it) }

        // ⑦ 模型文件
        var modelsDeleted = false
        if (deleteModelFiles) {
            modelsDeleted = runCatching { File(context.filesDir, "models").deleteRecursively() }
                .onFailure { failures += step("models", it) }
                .getOrDefault(false)
        }

        // 记忆表（库文件已删，这里只是把内存态清干净，防止残留引用）
        runCatching { memory.deleteAll() }.onFailure { failures += step("memory", it) }

        AkeLog.w(TAG, "wipeEverything done (modelsDeleted=$modelsDeleted failures=${failures.size})")
        WipeResult(modelsDeleted = modelsDeleted, failures = failures)
    }

    private fun dbFile(): File? = runCatching { context.getDatabasePath(AkeDatabase.DB_FILE_NAME) }.getOrNull()

    /** 统一的一步失败记账：日志里留一份（便于捞），同时作为「说给用户听」的一条失败项。 */
    private fun step(what: String, t: Throwable): String {
        AkeLog.w(TAG, "$what delete failed: ${t.message}")
        return "$what: ${t.message ?: t.javaClass.simpleName}"
    }

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

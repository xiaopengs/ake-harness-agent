package com.ake.ui.spi

import android.content.Context
import com.ake.core.ports.LocalSettings
import com.ake.core.ports.SettingsStorePort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置页里**能真正生效**的那几项的本地暂存。
 *
 * ## 哪些设置是真生效的，哪些只是展示
 *
 * | 设置项 | 是否生效 | 落点 |
 * |---|---|---|
 * | 域名白名单 | ✅ 真生效 | 下发到 `TaskSpec.allowHosts`，`DomainPolicy.isInSessionScope` 会消费它 |
 * | 最大步数 | ✅ 真生效 | 下发到 `TaskSpec.maxSteps` |
 * | 上下文预算档位 | ❌ 只读 | 由 `MemoryTier` 决定（`LoopBudget.forTier`），目前没有 Setter |
 * | 单步 / 总时长超时 | ❌ 只读 | 同上，写在 `LoopBudget` 里 |
 * | 域名黑名单 | ❌ 只读 | `DomainPolicy.blacklist` 是不可变快照，没有 Store |
 *
 * 「只读」那几项不是偷懒：它们属于 `agent` 的硬预算表，
 * 在没有 `DomainPolicyStorePort` / `BudgetOverridePort` 之前，**UI 不能靠自己存一份来假装生效** ——
 * 那会让用户在设置里调了参数、任务却跑在另一套预算上，是最难排查的一类不一致。
 *
 * ## ★ 与 MCP 清单同样的过渡性质
 *
 * 这里用 `org.json` 手写、落在 `filesDir/settings/ui_local.json`，
 * 与 `AndroidPrivacyPort` 的清除范围对齐（会被一并删掉）。
 * 等 `:data` 提供正式的设置 Store，把本类替换掉即可。
 *
 * ## ★ 2026-10 跨层依赖整改：本类现在**实现** `core:ports` 的 [SettingsStorePort]
 *
 * 此前它自带一份 `Values` 数据类，字段与 `LocalSettings` **逐字段同构** —— 这就是典型的两份定义：
 * 谁在 Port 侧加一个字段，UI 这边不会报错，只会静默读不到。
 * 现在删掉 `Values`、直接实现 Port，字段只有一份。
 *
 * ⚠ **给邝随行的交接事项**：`:data` 一旦落地它自己的 `SettingsStorePort` 实现，
 *   请删掉 `UiRuntimeModule.bindLocalSettings` 那一条 `@Binds`，否则 duplicate binding。
 */
@Singleton
public class UiLocalSettings @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : SettingsStorePort {

    private val mutex = Mutex()
    private val file: File get() = File(File(ctx.filesDir, "settings"), "ui_local.json")
    private val _values = MutableStateFlow(LocalSettings())
    private val loaded = AtomicBoolean(false)

    /**
     * 首次订阅时装载一次，之后由 [update] 推进。
     *
     * ★ 刻意用 `onStart` 而不是让调用方手工 `refresh()`：此前 `SettingsViewModel.init`
     *   里那句 `settings.refresh()` 是**调用方必须记得调用**的隐式契约，
     *   少调一次就会静默读到默认值 —— 这类「靠人记住」的时序要求迟早会漏。
     */
    override fun values(): Flow<LocalSettings> = _values.asStateFlow().onStart { ensureLoaded() }

    override suspend fun update(v: LocalSettings): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            _values.value = v
            runCatching {
                file.parentFile?.mkdirs()
                val o = JSONObject()
                val arr = JSONArray()
                for (h in v.allowHosts) arr.put(h)
                o.put("allowHosts", arr)
                if (v.maxSteps != null) o.put("maxSteps", v.maxSteps)
                o.put("showDomPayload", v.showDomPayload)
                file.writeText(o.toString())
            }
        }
    }

    /** 清空全部设置。一键清除走的就是这里（会被 `WipeEverythingUseCase` 一并删文件）。 */
    override suspend fun clearAll(): Unit = update(LocalSettings())

    private suspend fun ensureLoaded(): Unit {
        if (loaded.compareAndSet(false, true)) refresh()
    }

    private suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = file
            if (!f.exists()) return@withLock
            runCatching {
                val o = JSONObject(f.readText())
                val hosts = ArrayList<String>()
                val arr = o.optJSONArray("allowHosts")
                if (arr != null) for (i in 0 until arr.length()) hosts += arr.optString(i)
                _values.value = LocalSettings(
                    allowHosts = hosts,
                    maxSteps = if (o.has("maxSteps")) o.optInt("maxSteps") else null,
                    showDomPayload = o.optBoolean("showDomPayload", true),
                )
            }
        }
    }
}

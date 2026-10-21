package com.ake.ui.spi

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
 */
@Singleton
public class UiLocalSettings @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    public data class Values(
        /** 任务级域名白名单。空集 = 不限（由 `DomainPolicy` 的跨域名确认兜底）。 */
        val allowHosts: List<String> = emptyList(),
        /** 最大步数。`null` = 跟随档位默认值。 */
        val maxSteps: Int? = null,
        /** 是否显示「Agent 看到的 DOM 原文」。默认开 —— 这是本 App 的可解释性卖点。 */
        val showDomPayload: Boolean = true,
    )

    private val mutex = Mutex()
    private val file: File get() = File(File(ctx.filesDir, "settings"), "ui_local.json")
    private val _values = MutableStateFlow(Values())
    public val values: Flow<Values> = _values.asStateFlow()

    public suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = file
            if (!f.exists()) return@withLock
            runCatching {
                val o = JSONObject(f.readText())
                val hosts = ArrayList<String>()
                val arr = o.optJSONArray("allowHosts")
                if (arr != null) for (i in 0 until arr.length()) hosts += arr.optString(i)
                _values.value = Values(
                    allowHosts = hosts,
                    maxSteps = if (o.has("maxSteps")) o.optInt("maxSteps") else null,
                    showDomPayload = o.optBoolean("showDomPayload", true),
                )
            }
        }
    }

    public suspend fun update(v: Values): Unit = withContext(Dispatchers.IO) {
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
}

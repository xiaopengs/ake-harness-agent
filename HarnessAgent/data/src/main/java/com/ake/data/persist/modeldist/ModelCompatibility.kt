package com.ake.data.persist.modeldist

/**
 * 兼容矩阵判定（架构硬规则：**双向声明** `min` / `max`）。
 *
 * ## 为什么必须双向，只有下界不够
 *
 * 只有 `min_app_version_code` 时，你能挡住「老 App 装新模型」，但挡不住
 * **「新 App 装了老模型」** —— 而后者才是真会出事的方向：
 * App 升级后换了加载路径 / 分词器 / 工具 schema，老模型在这个新壳上表现为
 * 「能加载、能出字，但工具调用永远格式错」，这类故障不会崩、不会报错，
 * 只会静默地把成功率打下去，且**无法在不发版的情况下止血**。
 *
 * 有了上界，止血就是改一行配置：把 `max_app_version_code` 钉到出问题的前一个版本。
 *
 * ## 边界语义（含两端）
 *
 * `min <= appVersionCode <= max` ⇒ 可用。**恰好等于 min 或 max 都算兼容**，
 * 因为运维写 `max=199` 的意思是「199 及以下是安全的」，不是「199 不安全」。
 * 这里一旦写成排他，配置作者与代码之间就差一位，而差一位的后果是整批设备被误判。
 */
public sealed interface CompatVerdict {

    public data object Compatible : CompatVerdict

    /** App 太老：模型要求更高的壳（通常是新算子 / 新分词器）。 */
    public data class AppTooOld(val appVersionCode: Int, val minAppVersionCode: Int) : CompatVerdict

    /** App 太新：模型在这个壳上有已知问题（止血用）。 */
    public data class AppTooNew(val appVersionCode: Int, val maxAppVersionCode: Int) : CompatVerdict

    /** 条目不是 ACTIVE（DEPRECATED / REVOKED），不作为新装目标。 */
    public data class EntryNotActive(val state: ModelEntryState) : CompatVerdict
}

public object ModelCompatibility {

    public fun evaluate(entry: ModelManifestEntry, appVersionCode: Int): CompatVerdict {
        if (entry.state != ModelEntryState.ACTIVE) return CompatVerdict.EntryNotActive(entry.state)
        if (appVersionCode < entry.minAppVersionCode) {
            return CompatVerdict.AppTooOld(appVersionCode, entry.minAppVersionCode)
        }
        val max = entry.maxAppVersionCode
        if (max != null && appVersionCode > max) {
            return CompatVerdict.AppTooNew(appVersionCode, max)
        }
        return CompatVerdict.Compatible
    }

    public fun isUsable(verdict: CompatVerdict): Boolean = verdict is CompatVerdict.Compatible

    /** UI 归因用的 i18n key。文案是 `:ui` 的职责，这里只给 key（架构 R5）。 */
    public fun reasonKeyOf(verdict: CompatVerdict): String = when (verdict) {
        CompatVerdict.Compatible -> "err_none"
        is CompatVerdict.AppTooOld -> "err_model_app_too_old"
        is CompatVerdict.AppTooNew -> "err_model_app_too_new"
        is CompatVerdict.EntryNotActive -> when (verdict.state) {
            ModelEntryState.REVOKED -> "err_model_revoked"
            ModelEntryState.DEPRECATED -> "err_model_deprecated"
            ModelEntryState.ACTIVE -> "err_none"
        }
    }
}

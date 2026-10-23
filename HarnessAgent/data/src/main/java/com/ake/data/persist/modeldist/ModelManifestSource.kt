package com.ake.data.persist.modeldist

import com.ake.core.common.AppError
import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.common.SystemClock

/**
 * manifest 的**唯一来源**：把「缓存 / 网络 / 内置兜底」三个来源收敛成一个答案。
 *
 * ## 它在链路里的位置（本轮通电的那根线）
 *
 * ```
 *  ModelManifestSource.load()  ──► ModelManifest（已验签）
 *          │
 *          ▼
 *  ModelResolver.resolve(manifest, installed, appVersionCode, inventory)   ← `:llm` 调用
 *          │
 *          ▼
 *  ModelResolution.Current / UseLocal / Download / …
 * ```
 *
 * 上一轮 `resolve() / probe() / UseLocal` **运行时不可达**，根因就是最左边这一格是空的：
 * 工程里没有任何东西能产出 `ModelManifest`，于是调用方拿不到入参，
 * 而 R8 顺理成章地把整条链当成死代码 shrink 掉。本类就是把这格填上。
 *
 * ## 三级来源与优先级
 *
 *  | 级别 | 来源            | 何时用                     | 校验                       |
 *  |------|-----------------|----------------------------|----------------------------|
 *  | ①    | 磁盘缓存        | 总是先用（含断网）          | **每次读都重新验签**        |
 *  | ②    | 网络（HTTPS）   | 需要刷新时（见下）          | 验签 + 版本不得回退         |
 *  | ③    | 内置兜底        | ① ② 都没有时（首次启动）    | 验签（同样必须是我们签的）  |
 *
 * ## 刷新时机：**过期不失效，失败不抛弃**
 *
 * 两个容易搞反的点：
 *  - `refreshIntervalMillis` 是**节流**，不是 TTL。缓存**永不因时间过期** ——
 *    一个半月前的 manifest 也比「没有 manifest」有用得多（用户手上那份模型照样能用）。
 *  - 刷新失败**不清缓存、不报错给上层**（只是记一条 warn）。
 *    把一次网络抖动变成「模型不可用」是纯粹的自我伤害。
 *
 * ## 版本回退防护（为什么 `manifest_version` 必须单调）
 *
 * 攻击者（或配错的 CDN）可以重放一份**旧的、签名合法**的 manifest，
 * 把 ACTIVE 指针推回一个已经被 REVOKED 的模型。所以刷新时会比较 `manifest_version`：
 * 新值比缓存里的还小 ⇒ 拒绝（MANIFEST_DOWNGRADE），且**不写缓存**。
 * 这与 `ModelManifest.manifestVersion` 那条「回滚也照样 +1」的服务端约定是成对的两半。
 */
public class ModelManifestSource(
    private val config: ModelManifestConfig,
    private val transport: ManifestTransport,
    private val parser: ModelManifestParser,
    private val cache: ManifestCache? = null,
    private val clock: Clock = SystemClock,
) {

    @Volatile
    private var lastAttemptAtMillis: Long = NEVER

    @Volatile
    private var lastGood: ModelManifest? = null

    /**
     * 最后一次成功拿到的 manifest（**可能来自缓存也可能来自网络**）。
     * 只用于诊断 / 日志，不参与任何判定 —— 判定一律走 [load] 的返回值。
     */
    public val cached: ModelManifest? get() = lastGood

    /**
     * 拿一份可用的 manifest：先本地，必要时再联网。
     *
     * 这是调用方（`:llm`）唯一该用的入口 —— 它保证「有网用新的，没网用旧的，都没有才报错」。
     */
    public fun load(): Outcome<ModelManifest> {
        val local = current()
        if (!shouldRefresh()) return local
        // 刷新失败时：本地有就用本地的（可能旧但已验签），都没有才把网络错误报上去。
        val fresh = refresh()
        return if (fresh is Outcome.Ok) fresh else if (local is Outcome.Ok) local else fresh
    }

    /** **不联网**：缓存 → 内置兜底。冷启动、断网、节流窗口内都走这条。 */
    public fun current(): Outcome<ModelManifest> {
        val raw = cache?.read()
        if (!raw.isNullOrBlank()) {
            when (val parsed = parse(raw)) {
                is Outcome.Ok -> return Outcome.Ok(parsed.value.also { lastGood = it })
                is Outcome.Err ->
                    // ★ 缓存坏了**不删**：留着它至少能被 `refresh()` 覆盖，
                    //   删了就会在「解析 bug + 断网」的组合下彻底没有 manifest。
                    AkeLog.w(TAG, "缓存 manifest 不可用（${parsed.error.code}）⇒ 继续找内置兜底")
            }
        }
        val bundled = config.bundledManifest
        if (!bundled.isNullOrBlank()) {
            when (val parsed = parse(bundled)) {
                is Outcome.Ok -> return Outcome.Ok(parsed.value.also { lastGood = it })
                is Outcome.Err -> AkeLog.w(TAG, "内置 manifest 不可用：${parsed.error.code}")
            }
        }
        return Outcome.Err(
            AppError(
                ModelDistErrorCodes.MANIFEST_UNAVAILABLE,
                "err_manifest_unavailable",
                null,
                Severity.HIGH,
            ),
        )
    }

    /**
     * 联网刷新。**成功才写缓存**，失败一律保留现状。
     *
     * @return 失败时返回 [Outcome.Err]，调用方据此回退到 [current] 的结果。
     */
    @Synchronized
    public fun refresh(): Outcome<ModelManifest> {
        lastAttemptAtMillis = clock.nowMillis()

        if (!config.isConfigured) {
            return Outcome.Err(
                AppError(
                    ModelDistErrorCodes.MANIFEST_NOT_CONFIGURED,
                    "err_manifest_not_configured",
                    null,
                    Severity.MEDIUM,
                ),
            )
        }

        when (val fetched = transport.fetch(config.manifestUrl)) {
            is Outcome.Err -> {
                AkeLog.w(TAG, "拉取失败：${fetched.error.code} ⇒ 保留现有 manifest")
                return Outcome.Err(fetched.error)
            }
            is Outcome.Ok -> {
                val parsed = parse(fetched.value)
                if (parsed is Outcome.Err) {
                    // ★★ 验签/解析失败**绝不写缓存**：否则一份坏 manifest 会被持久化，
                    //    之后即使网络恢复、即使重启，都一直用这份坏的 —— 那正是 fail-closed 要防的。
                    AkeLog.w(TAG, "manifest 校验失败：${parsed.error.code} ⇒ 不写缓存")
                    return Outcome.Err(parsed.error)
                }
                val manifest = (parsed as Outcome.Ok).value

                val previous = lastGood
                if (previous != null && manifest.manifestVersion < previous.manifestVersion) {
                    AkeLog.w(
                        TAG,
                        "manifest_version 回退（${previous.manifestVersion} → ${manifest.manifestVersion}）⇒ 拒绝",
                    )
                    return Outcome.Err(
                        AppError(
                            ModelDistErrorCodes.MANIFEST_DOWNGRADE,
                            "err_manifest_downgrade",
                            null,
                            Severity.HIGH,
                        ),
                    )
                }

                if (cache?.write(fetched.value) == false) {
                    AkeLog.w(TAG, "manifest 缓存写入失败（不影响本次使用）")
                }
                lastGood = manifest
                return Outcome.Ok(manifest)
            }
        }
    }

    /**
     * 是否**该**去拉一次。
     *
     * 注意它是「距上次**尝试**」而不是「距上次**成功**」：网络一直失败时，
     * 按成功时间算会退化成每次都重试，把失败的网络请求打成风暴。
     */
    public fun shouldRefresh(): Boolean {
        val last = lastAttemptAtMillis
        if (last == NEVER) return true
        return clock.nowMillis() - last >= config.refreshIntervalMillis
    }

    private fun parse(raw: String): Outcome<ModelManifest> = parser.parse(raw)

    private companion object {
        const val TAG: String = "ModelManifestSource"
        const val NEVER: Long = Long.MIN_VALUE
    }
}

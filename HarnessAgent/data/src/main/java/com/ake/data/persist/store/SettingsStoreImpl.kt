package com.ake.data.persist.store

import androidx.room.withTransaction
import com.ake.core.common.AkeLog
import com.ake.core.common.Clock
import com.ake.core.ports.LocalSettings
import com.ake.core.ports.SettingsStorePort
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.entity.AppSettingsEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * `SettingsStorePort` 的真实现（落 Room，`app_settings` 单行表）。
 *
 * ## ★ 为什么 `values()` 是 Flow 而不是一次性读
 *
 * 契约里那条注释已经把理由写死了：「替换 UI 侧手工 `refresh()` 的时机问题」。
 * 这里补上实现侧的事实：Room 的 Flow **在事务提交后**重发，
 * 于是「设置页改了白名单 → 任务页下一次建任务就能用上」是自动成立的，
 * 不需要调用方记得在某个时机重查一次。
 *
 * 而 `:ui` 那份 `UiLocalSettings` 是 `MutableStateFlow` + `onStart { ensureLoaded() }`：
 * 两条路径都能用，但那份的**写入与读取走的是两份不同的真相**
 * （内存 `_values` 与文件），一旦文件写失败，UI 显示的值与库里的值就分叉了。
 * 这里只有一个真相：**库**。
 *
 * ## `clearAll()` 为什么要真的删行，而不是写回默认值
 *
 * 契约原文：「必须与 `WipePort` 的清除范围一致 —— 否则一键清除之后会留下一批孤儿文件」。
 * 写回默认值确实能让 UI 读到默认值，但 `app_settings` 里会留下一行
 * 「用户从未主动设置过、却存在一行」的记录。
 * 清除的语义是**回到未初始化**，不是「变成默认值」—— 这两者一旦被混淆，
 * 「用户到底设没设过白名单」就再也问不出来了。
 */
@Singleton
public class SettingsStoreImpl @Inject constructor(
    private val db: AkeDatabase,
    private val clock: Clock,
) : SettingsStorePort {

    /**
     * 当前值，随变更持续 emit。
     *
     * ★ [distinctUntilChanged] 是必需的而不是优化：`LocalSettings` 是 `data class`，
     *   Room 的 Flow 在同一行被其它事务触碰时也会重发，
     *   不去重会让下游（ViewModel 的 `collect` → Compose 重组）为同一份值反复重算。
     *
     * ★ 首次订阅时若库里还没有这一行，emit 一份 [LocalSettings] 的默认值 ——
     *   与 `onStart` 里那次装载不同，这里**不去insert 一行**：
     *   「读一次」不该有写副作用，否则「打开设置页」这个动作会凭空产生一条记录。
     */
    override fun values(): Flow<LocalSettings> = db.configDao()
        .observeSettings(AppSettingsEntity.SINGLE_ROW_ID)
        .map { row -> row?.let(::toDomain) ?: LocalSettings() }
        .distinctUntilChanged()

    override suspend fun update(v: LocalSettings) {
        runCatching {
            db.withTransaction {
                db.configDao().upsertSettings(toEntity(v, clock.nowMillis()))
            }
        }.onFailure { AkeLog.w(TAG, "update failed: ${it.message}") }
    }

    override suspend fun clearAll() {
        runCatching {
            db.withTransaction { db.configDao().clearSettings() }
        }.onFailure { AkeLog.w(TAG, "clearAll failed: ${it.message}") }
    }

    // ──────────────────────────────────────── 映射

    /**
     * 行 → 领域对象。
     *
     * ★ `maxSteps` 的 `null` 语义必须原样带回：`0` 与 `null` 在这里是两回事
     * （`0` = 一步都不许跑，`null` = 跟随设备档位）。
     * 用 `if (it == 0) null else it` 这种「顺手」的写法会让「设为 0」这个操作永远存不住。
     */
    internal fun toDomain(row: AppSettingsEntity): LocalSettings = LocalSettings(
        allowHosts = FlatJson.decodeList(row.allowHostsJson),
        maxSteps = row.maxSteps,
        showDomPayload = row.showDomPayload != 0,
    )

    internal fun toEntity(v: LocalSettings, updatedAt: Long): AppSettingsEntity = AppSettingsEntity(
        id = AppSettingsEntity.SINGLE_ROW_ID,
        allowHostsJson = FlatJson.encodeList(v.allowHosts),
        maxSteps = v.maxSteps,
        showDomPayload = if (v.showDomPayload) 1 else 0,
        updatedAt = updatedAt,
    )

    public companion object {
        public const val TAG: String = "SettingsStore"
    }
}

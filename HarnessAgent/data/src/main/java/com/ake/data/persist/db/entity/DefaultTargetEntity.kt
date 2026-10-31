package com.ake.data.persist.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ★★ 全局默认目标（v5）。**单行表**（与 `app_settings` 同一手法：占位主键恒为 0）。
 *
 * ## ★ 为什么是**新表**，而不是给 `app_settings` 加一列
 *
 * 直觉是「它就是一个设置项，塞进 `app_settings` 最省事」。**不要**：
 *
 * `SettingsStoreImpl.update()` 走的是 `configDao().upsertSettings(toEntity(v, now))`，
 * 而 `upsertSettings` 是 `@Insert(onConflict = REPLACE)` —— **整行替换**。
 * `toEntity()` 只认 `LocalSettings` 的三个字段 ⇒ 任何加到 `app_settings` 上、
 * 而 `LocalSettings` 不认识的列，都会在**每次保存设置时被静默重置成默认值**。
 *
 * 那不是「将来要小心」，是「今天写下去、下次打开设置页就没了」。
 * ⇒ 另起一张只有自己写的单行表，成本是迁移多一条 `CREATE TABLE`，
 *   换来的是**没有别人能覆盖它**。
 *
 * ## ★ `model_target` 可空，且**不加外键**
 *
 *  · 可空：`NULL` = 「从未设置过」。`'LOCAL'` = 「用户显式选了端侧」。
 *    这两件事在库里必须分得开（见 `DefaultTargetStore.default()` 的注释）。
 *  · 不加外键：落的是 `DialogTargetCodec.encode()` 的文本
 *    （`LOCAL` / `CLOUD:<credId>`），**前缀编码没法建外键**。
 *    ★ 而且刻意**不**希望凭证被删时这行被 `ON DELETE SET NULL` 悄悄清空 ——
 *      「默认后端指向一个已被删除的凭证」是用户必须看见的事实，
 *      静默清空就又变成「莫名变成本地了」。
 */
@Entity(tableName = "default_dialog_target")
data class DefaultTargetEntity(
    /** 恒为 [SINGLE_ROW_ID]。Room 要求主键非空 ⇒ 用占位主键而不是 UUID。 */
    @PrimaryKey @ColumnInfo(name = "id") val id: Int,

    /**
     * `DialogTargetCodec.encode()` 的产物。`NULL` = 从未设置过。
     *
     * ⚠ 落库文本一旦无法识别，`DialogTargetCodec.decode()` 会产出
     *    `DialogTarget.Unrecognized`，**不会**被静默降级成端侧。
     */
    @ColumnInfo(name = "model_target") val modelTarget: String?,

    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    @ColumnInfo(name = "schema_v", defaultValue = "1") val schemaV: Int,
) {
    companion object {
        const val SINGLE_ROW_ID: Int = 0
        const val SCHEMA_VERSION: Int = 1
    }
}

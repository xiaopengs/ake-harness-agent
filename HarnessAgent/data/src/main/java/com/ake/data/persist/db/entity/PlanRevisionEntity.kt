package com.ake.data.persist.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 计划版本（v6）。主键 `(task_id, revision)`。
 *
 * ## 为什么主键是复合键，而不是自增 id
 *
 * 这张表的每一行都是「某一个任务的某一版计划」，而 **CAS 更新**（`WHERE revision = ?`）
 * 就是全部并发控制手段。用自增 id 的话，`UPDATE ... WHERE id = ?` 也能做 CAS，
 * 但查询"某个任务的最新计划"就得再套一层 `MAX(id)` 子查询 ——
 * 复合主键天然让 `(task_id, revision DESC)` 走主键索引，一次索引扫描就到位。
 *
 * ## 为什么保留旧版本（不 UPDATE 覆盖）
 *
 * 用户先说"确认第 1 版"，后来说"我确认的不是那个"——这时候要能拿出第 1 版原文。
 * 覆盖式更新会把这条证据抹掉，而多存一版计划只有几百字节。
 *
 * ## `steps_text` 不加密
 *
 * 计划里只有"做什么"（`read_file` / `summarize`），**没有文件内容、没有用户原话的完整副本**
 * （目标另存 `goal`，也只存模型改写后的短句）。加密会让计划确认页每次都要解密一遍，
 * 而这一页是用户要反复看、反复改的。
 */
@Entity(
    tableName = "plan_revision",
    primaryKeys = ["task_id", "revision"],
    indices = [Index(name = "idx_plan_task_created", value = ["task_id", "created_at"])],
)
data class PlanRevisionEntity(
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "revision") val revision: Int,
    /** 模型改写后的目标短句（≤200 字）。 */
    @ColumnInfo(name = "goal") val goal: String,
    /**
     * 一行一步：`kind|target`，换行分隔；`target` 里的 `\` 与 `|` 转义。
     *
     * ★ 为什么不是 JSON：`:data` 没有可用的 JSON 解析器（`org.json` 在纯 JVM 单测里是桩），
     *   而 `:agent` 的 `JsonTolerant` 在 L4，L3 不能反向依赖。为一张表的三个字段
     *   引一套序列化插件不划算。编码规则见 `PlanCodec`，它有独立单测。
     */
    @ColumnInfo(name = "steps_text") val stepsText: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "schema_v", defaultValue = "1") val schemaV: Int = 1,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

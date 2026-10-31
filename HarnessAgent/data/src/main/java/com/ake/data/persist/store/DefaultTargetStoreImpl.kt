package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.ports.DefaultTargetStore
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetCodec
import com.ake.data.persist.db.dao.DefaultTargetDao
import com.ake.data.persist.db.entity.DefaultTargetEntity

/**
 * ★★ `DefaultTargetStore` 的 Room 实现（v5，`default_dialog_target` 单行表）。
 *
 * ## ★ 它修的是什么
 *
 * `chat_session.model_target` 的列默认值是 `'LOCAL'`，而新建会话时没人去读
 * "用户在设置页选了什么" ⇒ **用户选了云端、新会话仍然静默走本地**。
 * 这不是崩溃，是与红线同一类的错误：**用户以为自己在用某个模型，实际在用另一个**。
 *
 * 修法分两半，本类只做**前半**：给"全局默认"一个持久化的落点。
 * 后半（会话未登记时用它）在 `:inference` 的 `DefaultModelRouter` 里 ——
 * `targets.of(sessionId) ?: defaults.default()`，**不属本轮文件边界，需主理人转派**。
 *
 * ## ★★ 三条不变量（改这个文件时别破坏）
 *
 *  ① **`default()` 永不返回 null**：未设置 ⇒ [DialogTarget.Device]。
 *     端侧是安全默认，且它与 `model_target` 列的 `'LOCAL'` 同一套语义。
 *  ② **库里 `NULL` 与 `'LOCAL'` 仍是两种状态**（"从未设置过" vs "显式选了端侧"），
 *     只是对外表现一致 ⇒ 将来要做引导提示时不需要改 schema。
 *  ③ **存什么返回什么**：不因目标指向的凭证已被粉碎而静默降级成 [DialogTarget.Device]。
 *     那种降级正是本轮要消灭的错误类型 —— 「莫名变成本地了」没有任何解释。
 *
 * ## ★ 为什么它必须是 Room（持久化）
 *
 * 内存实现重启即丢，用户"选了云端"这件事在下次冷启动后静默变回端侧 ——
 * 与 [DialogTargetStoreImpl] 的类注释里写的是同一个理由。
 *
 * ## ★ 编解码仍然只有一份
 *
 * 文本形态全在 [DialogTargetCodec]（`:core:ports`）。本类只负责"存哪、怎么写"。
 */
public class DefaultTargetStoreImpl internal constructor(
    private val dao: DefaultTargetDao,
    private val clock: Clock,
) : DefaultTargetStore {

    override suspend fun default(): DialogTarget {
        val raw = runCatching { dao.modelTargetOf(DefaultTargetEntity.SINGLE_ROW_ID) }
            .onFailure { AkeLog.w(TAG, "read default target failed: ${it.message}") }
            .getOrNull()
        // ★ 没有这一行 / 列为 NULL ⇒ 从未设置过 ⇒ 端侧（安全默认）。
        //   ⚠ 这里**不**补写一行：读一次不该有写副作用。
        return DialogTargetCodec.decode(raw)
    }

    override suspend fun setDefault(target: DialogTarget): Outcome<Unit> {
        // ★ Unrecognized 也原样落库（DialogTargetCodec.encode 的既有纪律：
        //   未知文本不改写，否则「曾经不认识」这件事会被抹掉）。
        val row = DefaultTargetEntity(
            id = DefaultTargetEntity.SINGLE_ROW_ID,
            modelTarget = DialogTargetCodec.encode(target),
            updatedAt = clock.nowMillis(),
            schemaV = DefaultTargetEntity.SCHEMA_VERSION,
        )
        return runCatching { dao.upsert(row) }
            .fold(
                onSuccess = { Outcome.Ok(Unit) },
                onFailure = { t ->
                    AkeLog.w(TAG, "setDefault failed: ${t.message}")
                    Outcome.Err(AppError(AppError.CODE_IO, "err_default_target_write", t))
                },
            )
    }

    /**
     * ★ 「用户到底有没有显式选过」—— 留给将来的引导提示用。
     *
     * 不进 Port：今天没人需要它（未设置一律按端侧走），
     * 但它证明了 ② 那条不变量在库里是**真**的，而不是一句注释。
     */
    public suspend fun isExplicitlySet(): Boolean =
        runCatching { dao.rowCount(DefaultTargetEntity.SINGLE_ROW_ID) }.getOrDefault(0) > 0

    /** ★ 一键清除用：物理删行，回到「未初始化」而不是「变成默认值」。 */
    public suspend fun clear(): Int = runCatching { dao.deleteAll() }.getOrDefault(0)

    public companion object {
        public const val TAG: String = "DefaultTargetStore"
    }
}

package com.ake.inference

import com.ake.core.common.Outcome
import com.ake.core.ports.DefaultTargetStore
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `DefaultTargetStore` 的内存实现（与 [InMemoryDialogTargetStore] 同一手法）。
 *
 * ## ★ 它存在的理由
 *
 * `DefaultModelRouter` 的三级优先（会话显式 > 全局默认 > 端侧）是**纯 JVM** 的，
 * 单测里挂不了 Room ⇒ 需要一个不依赖 Android、可脚本化的实现。
 *
 * ## ★★ 「从未设置过」与「显式设成端侧」必须是**两种状态**
 *
 * [raw] 为 `null` ⇒ 从未设置；为 `DialogTargetCodec.VALUE_DEVICE`（`"LOCAL"`）
 * ⇒ 显式设成端侧。两者 [default] 的返回值**相同**（都是 [DialogTarget.Device]），
 * 但 [isExplicitlySet] 能分开它们 —— 与 `:data` 里 `default_dialog_target.model_target`
 * **可空列**是同一套语义的两面（NULL vs `'LOCAL'`）。
 *
 * ⚠ 这条区分是**刻意**保留的：路由层看不出来，不代表它可以被抹平。
 *   将来若要做「你还没选过后端」的引导，靠的就是 [isExplicitlySet]。
 *
 * ## ⚠ 生产**不要**绑定这个实现
 *
 * 它没有持久化：进程一死就没了 ⇒ 冷启动后用户选的云端默认会**丢**，
 * 那正是本轮要修的那类错误。`:app` 必须注入 `:data` 的
 * `DefaultTargetStoreImpl`（Room，`default_dialog_target` 表，v5 迁移产物）。
 */
public class InMemoryDefaultTargetStore : DefaultTargetStore {

    private val mutex = Mutex()

    @Volatile
    private var raw: String? = null

    /**
     * @return 已设置的默认值；从未设置过 ⇒ [DialogTarget.Device]（端侧，安全默认）。
     *
     * ★ **不写回**：读操作绝不把「未设置」固化成一行 `"LOCAL"`，
     *   否则 [isExplicitlySet] 立刻失去意义。
     */
    override suspend fun default(): DialogTarget = mutex.withLock {
        raw?.let(DialogTargetCodec::decode) ?: DialogTarget.Device
    }

    /** 覆盖式设置（单行语义）。未知态按 `DialogTargetCodec` 的纪律**原样落库**。 */
    override suspend fun setDefault(target: DialogTarget): Outcome<Unit> = mutex.withLock {
        raw = DialogTargetCodec.encode(target)
        Outcome.Ok(Unit)
    }

    /** ★ 与 [default] 的差别：`false` ⇒ 从未设置过。 */
    public suspend fun isExplicitlySet(): Boolean = mutex.withLock { raw != null }

    /** 测试用：直接观察落库文本。 */
    public suspend fun rawOf(): String? = mutex.withLock { raw }

    /** 测试用：复位到「从未设置过」。 */
    public suspend fun clear(): Unit = mutex.withLock { raw = null }
}

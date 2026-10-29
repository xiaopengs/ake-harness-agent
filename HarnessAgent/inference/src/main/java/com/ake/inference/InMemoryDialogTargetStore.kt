package com.ake.inference

import com.ake.core.common.Outcome
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetCodec
import com.ake.core.ports.DialogTargetStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `DialogTargetStore` 的内存实现。
 *
 * ## 为什么它必须存在（即使生产用 Room）
 *
 * `DefaultModelRouter` 的派发 / 分相逻辑是**纯 JVM** 的，单测里不能挂 Room。
 * 于是需要一个「不依赖 Android、可脚本化」的实现来跑路由单测（§10 P2）。
 * 生产实现由 `:data`（v3→v4 迁移后的 `chat_session.model_target` 列）提供，
 * 由 `:app` 组合根注入 —— 与 `FieldCipher` 的注入手法一致。
 *
 * ## ★ 落库形态只有一份
 *
 * 文本形态的编解码全在 [DialogTargetCodec]（`:core:ports`），
 * 本类只负责「存哪、怎么并发安全」⇒ 不会出现第二份不等价的解析。
 */
public class InMemoryDialogTargetStore : DialogTargetStore {

    private val mutex = Mutex()
    private val rows = LinkedHashMap<String, String>()

    override suspend fun of(sessionId: String): DialogTarget? = mutex.withLock {
        rows[sessionId]?.let(DialogTargetCodec::decode)
    }

    override suspend fun select(sessionId: String, target: DialogTarget): Outcome<Unit> =
        mutex.withLock {
            rows[sessionId] = DialogTargetCodec.encode(target)
            Outcome.Ok(Unit)
        }

    /** 测试用：直接观察落库文本，验证 §7.5 的常量收口。 */
    public suspend fun rawOf(sessionId: String): String? = mutex.withLock { rows[sessionId] }
}

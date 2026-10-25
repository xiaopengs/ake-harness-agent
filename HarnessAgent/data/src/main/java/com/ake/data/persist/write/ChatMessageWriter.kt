package com.ake.data.persist.write

import com.ake.core.common.AkeLog
import com.ake.data.persist.db.dao.ChatMessageDao
import com.ake.data.persist.redact.PersistenceRedactionGate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 聊天消息写入器（§4.5）。**进程级单例**
 * （否则 §7 的「停在途写入」会停错泳道 —— 与 `WipeEverythingUseCase` 类注释里
 *  那条 `TraceBufferWriter` 的警告是同一条）。
 *
 * ## 它解决什么问题
 *
 * `ChatEvent.AssistantDelta` 是「新增的那一段」，一路流到 UI。它要不要落盘？
 *
 *  - **落 delta 流**：一条 512 token 的回答 ≈ 512 个 delta ⇒ `chat_message`
 *    从「万级」变成「百万级」、`(session_id, seq)` 唯一索引维护成本 ×512、
 *    复水要把 512 行拼回一条消息。**收益却几乎没有** —— 落盘的目的只有两个
 *    （冷恢复时用户不丢已看见的内容、复水时能重建历史），两者都只需要
 *    「截至某个时刻的完整文本」，不需要 delta 粒度。
 *  - **只在 turn 结束落一次**（上游 §3.1 ⑤ + R6）：丢的不是「一轮」，
 *    而是**用户已经看见的一轮**。流式气泡已在屏幕上渲染 10 秒，进程一被杀那条回答凭空消失 ——
 *    这是所有丢失形态里最坏的一种：**它有目击者**。而且没有行 ⇒ 连「已中断」
 *    这个诚实的表达都做不到（UI 无从渲染）。
 *
 * ⇒ **本文裁决：delta 一行都不落，落「截至此刻的完整文本快照」，覆盖式写同一行。**
 *
 * ## 为什么是「per-messageId 单槽覆盖」而不是一个队列
 *
 * 队列的语义是「保序、每条都要处理」。但流式快照的语义恰恰相反：
 * **同一个 messageId 的旧快照毫无价值** —— 它必然被下一次覆盖。
 * 用队列 + DROP_OLDEST 会丢掉**最老**的（在单 messageId 场景下恰好是对的），
 * 但多会话/多消息时就变成「丢谁不确定」。
 * 单槽 `ConcurrentHashMap<messageId, Slot>` 把这个不确定性直接消灭：
 * 覆盖是 O(1)、永不失败、永不阻塞，且**语义上就是正确答案**。
 *
 * ## 三条纪律
 *
 *  1. **生成主循环永远不 `await` 落盘。** 主循环只做一次 `offerSnapshot()`
 *     （一次 map put，纳秒级，不可能抖动）。写盘在本类的单线程写作用域里，
 *     与 v2 §6.2 的单写者串行化是同一条约束。
 *  2. **② 泳道的 API 是非挂起的**（对齐既有 `ObservationStorePort.append(): Boolean`
 *     与 `PolicyDecisionSink.record(): Boolean` 的约定）。
 *     ★ 它**不是** `ChatSessionStorePort` 的方法 —— Port 只暴露 `append()`。
 *     流式落盘是 `:data` 的内部实现细节，`:chat` 与 `:ui` 都不该看见它（§4.4）。
 *  3. **加密在 drain 时做，不在 offer 时做。** offer 只存明文字符串；
 *     `gate.sealField()` 走 Keystore 缓存的密钥（**不是每次调 Keystore**），
 *     AES-GCM 2 KB ≈ 10–30 µs，可忽略。
 *  4. **终态提交必须在 drain 之后**（由 Store 保证顺序），否则「终态写进去了、
 *     最后一次快照后到才落地」会把完整文本覆盖回旧文本 —— **顺序错了就是静默回退**。
 *     终态提交前调用 [discardSlot] 把槽清掉。
 *
 * ## 节流参数怎么定出来的（写放大算账）
 *
 * ```
 * 一条 512 token 回答 ≈ 2,048 字符，生成 10 秒。
 *   按字符判据：2048 / 96 = 21 次      按时间判据：10,000 / 400 = 25 次
 *   ⇒ 实际写次数 = min(21, 25) ≈ 21 次（双条件是「且」，取更严的那个）
 * 单次 UPDATE（WAL, synchronous=NORMAL）：0.05–0.5 ms ⇒ 一轮累计 1–10 ms
 *   ⇒ 相对一轮 20–40 秒 = 0.03% ~ 0.05%，且全部发生在 writer 线程，主循环零感知
 * ```
 *
 * 降到 16 字符：128 次/轮、WAL 25 MB/天，收益只是丢失窗口从 ~400 ms 降到 ~60 ms —— **不值**。
 * 升到 512 字符：丢失窗口 ~4 秒，用户会看见明显的一段消失 —— **过头**。
 * **96 字符 ≈ 32 个 token ≈ 0.6 秒的生成量**，落在「感知不到」与「写放大可控」的交点上。
 */
public class ChatMessageWriter constructor(
    private val dao: ChatMessageDao,
    private val gate: PersistenceRedactionGate,
    private val metrics: PersistenceMetrics,
    private val writerContext: CoroutineContext,
    /** 单调时钟（节流判据用）。注入是为了让单测能推进时间。 */
    private val elapsedMs: () -> Long = { System.currentTimeMillis() },
    /** 墙钟（写 `updated_at` 用）。 */
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    /** ★ 排水间隔。生产 = [Throttle.MIN_INTERVAL_MS]；单测可以调小。 */
    private val drainIntervalMs: Long = Throttle.MIN_INTERVAL_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + writerContext)
    private val slots = ConcurrentHashMap<String, Slot>()
    private val dirty = AtomicInteger(0)

    /** ★ 非挂起。覆盖即成功，永不阻塞、永不失败。返回 false 仅用于观测埋点。 */
    public fun offerSnapshot(messageId: String, text: String): Boolean {
        val prev = slots.put(messageId, Slot(text = text, offeredAt = elapsedMs()))
        if (prev == null) dirty.incrementAndGet()
        return true
    }

    /** 终态提交后立刻调用：把槽清掉，防止 drain 晚于 commit 造成静默回退。 */
    public fun discardSlot(messageId: String) {
        if (slots.remove(messageId) != null) dirty.decrementAndGet()
    }

    /** 观测用：当前在途槽数。 */
    public fun pendingSlots(): Int = dirty.get()

    /** §7 一键清除：停掉一切在途写入。 */
    public fun stopAndDropQueued() {
        runCatching { scope.cancel() }
        slots.clear()
        dirty.set(0)
    }

    init {
        scope.launch { drainLoop() }
    }

    /**
     * 双条件 drain：**字符增量 AND 时间间隔**。
     *
     * ★ 时间条件是**兜底**而不是主判据：它防止「技能执行期间长时间不增量」
     * 导致快照一直不落（那时用户正盯着一个空白气泡）。
     */
    private suspend fun drainLoop() {
        while (scope.isActive) {
            delay(drainIntervalMs)
            if (dirty.get() == 0) continue
            // 快照一份 key 集合后逐个处理：处理期间新的 offer 会覆盖同一个槽，天然合并
            for (messageId in slots.keys) {
                val slot = slots[messageId] ?: continue
                if (elapsedMs() - slot.offeredAt < Throttle.MIN_INTERVAL_MS) continue
                if (slot.text.length - slot.writtenLength < Throttle.MIN_DELTA_CHARS) continue
                write(messageId, slot)
            }
        }
    }

    /**
     * ★ 只 UPDATE，绝不 REPLACE（§10.1 P6）。
     *
     * `REPLACE` 的语义是 DELETE + INSERT，它会触发 `route_decision.chat_message_id`
     * 的 `ON DELETE SET NULL` ⇒ 每 400 ms 清一次路由审计引用。
     */
    private suspend fun write(messageId: String, slot: Slot) {
        val sealed = runCatching { gate.sealField(slot.text, messageId.toByteArray()) }
            .getOrElse { t ->
                metrics.recordStateWriteFailure(t)
                return
            }
        val rows = runCatching {
            dao.writeSnapshot(
                messageId = messageId,
                contentEnc = sealed.blob,
                preview = slot.text.take(Throttle.PREVIEW_CHARS),
                now = wallMs(),
            )
        }.getOrElse { t ->
            metrics.recordStateWriteFailure(t)
            return
        }
        if (rows > 0) {
            slot.writtenLength = slot.text.length
            slot.writtenAt = elapsedMs()
        } else {
            // 0 行 = 该消息已不处于 STREAMING（已被 sweep 收敛 / 已提交）⇒ 清槽
            if (slots.remove(messageId) != null) dirty.decrementAndGet()
        }
    }

    /** 可见性仅为本类与单测（单测要读 `writtenLength` 断言节流真的生效）。 */
    internal fun slotOf(messageId: String): Slot? = slots[messageId]

    internal class Slot(
        val text: String,
        val offeredAt: Long,
        @Volatile var writtenLength: Int = 0,
        @Volatile var writtenAt: Long = 0L,
    )

    public object Throttle {
        /** ★ 主判据：字符增量。与 token 量成正比，与机型快慢无关。 */
        public const val MIN_DELTA_CHARS: Int = 96

        /** ★ 兜底判据：时间间隔。 */
        public const val MIN_INTERVAL_MS: Long = 400L

        public const val PREVIEW_CHARS: Int = 80

        /**
         * ★ 丢失窗口的诚实表述：进程被杀时最多丢「最后一次成功快照之后到终态提交之前」
         * 的内容，典型 ≤ 400 ms + 一次 drain 间隔 ≈ **0.4–0.8 秒**的生成量（约 20–40 token）。
         * 相比上游 R6 的「丢一整轮」改善一个数量级以上；且消息行**存在且标为 `ABORTED`**，
         * UI 能诚实显示「已中断」，而不是让内容凭空消失。
         */
        public const val DOC_LOSS_WINDOW_MS: Long = 800L
    }

    private companion object {
        const val TAG: String = "ChatMessageWriter"
    }

    init {
        AkeLog.i(TAG, "started (delta=${Throttle.MIN_DELTA_CHARS} interval=${Throttle.MIN_INTERVAL_MS}ms)")
    }
}

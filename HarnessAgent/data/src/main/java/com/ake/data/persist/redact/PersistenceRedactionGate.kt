package com.ake.data.persist.redact

import com.ake.core.common.FieldCipher
import com.ake.core.common.RedactionGate
import com.ake.core.common.RedactionResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # 落盘唯一脱敏闸口（数据库 §8.4 / F3(c)）
 *
 * **`scrub()` 与 `sealField()` 是唯一出口。任何 DAO 调用之前的文本都必须过它，
 * 不得靠调用点自觉。**
 *
 * 这句话不是口号，它有一条可执行的 CI 检查：
 *
 * ```
 * # :data:persist 里凡是给 *_enc 列赋值的语句，赋值右侧必须出现 gate.sealField(
 * rg -n "_enc\s*=" data/src --glob '!*Mapper*' | rg -v "gate\.sealField"
 * ```
 *
 * ## 为什么必须计数
 *
 * 脱敏命中要写进 `observation.redaction_count` / `redaction_types` / `task.redaction_count`。
 * 这不是为了合规表演：当回放发现「某一步的页面内容是 `[已屏蔽：检测到疑似提示注入内容]`」时，
 * 工程师必须能区分**这是脱敏干的**还是**模型失败导致的**，
 * 否则会花半天去查一个不存在的 bug（数据库 §8.3(e)）。
 *
 * ## 与 `:core:common/Redactor` 的分工
 *
 * `Redactor` 是**采集期**的纯函数脱敏（在 `:browser` 抽元素文本的那一刻就该调用）。
 * 这里是**落盘期**的最后一道。两道都要有，因为采集点可能有遗漏
 * （第三方 MCP 工具的返回不走我们的采集链路）。
 */
@Singleton
public class PersistenceRedactionGate @Inject constructor(
    cipher: FieldCipher,
) {

    /** 复用 core 侧的唯一闸门：那里已经把「没有 cipher 就抛而不是静默明文落盘」这条纪律写死了。 */
    private val gate = RedactionGate(cipher)

    data class Sealed(val blob: ByteArray, val redactionCount: Int, val redactionTypes: Set<String>)

    /** 返回「已脱敏的明文」。调用方再决定要不要加密。 */
    fun scrub(text: String): RedactionResult = gate.scrub(text)

    /** 「脱敏 + 加密」一步到位。**R1 字段一律走这个。** */
    fun sealField(text: String, aad: ByteArray? = null): Sealed {
        val (blob, count) = gate.sealField(text, aad)
        return Sealed(blob, count, gate.scrub(text).redactionTypes)
    }

    /**
     * @param aad 必须传 taskId 的字节 —— GCM 的 AAD 能防止密文被**跨行搬移**
     *            （把 A 任务的答案密文整段挪到 B 任务那一行）。
     */
    fun openField(blob: ByteArray, aad: ByteArray? = null): String? = gate.openField(blob, aad)

    /** 只脱敏不落盘的场景（如写通知栏文案）。 */
    fun scrubOnly(text: String): String = gate.scrub(text).text
}

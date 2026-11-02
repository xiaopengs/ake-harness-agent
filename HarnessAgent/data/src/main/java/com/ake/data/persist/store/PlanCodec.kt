package com.ake.data.persist.store

import com.ake.core.contract.PlanStep
import com.ake.core.contract.PlanStepKind

/**
 * `plan_revision.steps_text` 的编解码。
 *
 * ## 格式
 *
 * 一行一步：`kind|target`，行间用 `\n`。
 * `target` 里的 `\` 转义成 `\\`，`|` 转义成 `\|`，换行折成空格。
 *
 * ## 为什么不是 JSON
 *
 * 三个字段的结构不值得一套解析器：`:data` 拿不到 `:agent` 的 `JsonTolerant`（L3 不能依赖 L4），
 * `org.json` 在纯 JVM 单测里是抛异常的桩，引 `kotlinx-serialization` 又要动版本目录。
 * 而本格式的编解码一共 30 行，**能被逐分支覆盖**，且坏输入可以降级成空列表。
 *
 * ## 解码为什么宽容
 *
 * 这段文本来自上一次写入，不是模型输出。一次解析失败不该让"计划确认页"整页打不开 ——
 * 返回空列表，上层会当成"这一版计划不可用"另起一版。
 * 不认识的 `kind` 也丢弃：闭集之外的值执行期没有分支，留着只会制造"显示有、执行跳过"。
 */
internal object PlanCodec {

    private const val DELIM = '|'
    private const val ESC = '\\'
    private const val LINE = "\n"

    fun encode(steps: List<PlanStep>): String =
        steps.joinToString(LINE) { s -> s.kind + DELIM + escape(s.target) }

    fun decode(text: String): List<PlanStep> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<PlanStep>()
        for (line in text.split(LINE)) {
            if (line.isBlank()) continue
            val at = line.indexOf(DELIM)
            if (at <= 0) continue
            val kind = line.substring(0, at)
            if (kind !in PlanStepKind.ALL) continue
            val target = unescape(line.substring(at + 1))
            out += PlanStep(index = out.size, kind = kind, target = target)
        }
        return out
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            when (c) {
                ESC -> sb.append(ESC).append(ESC)
                DELIM -> sb.append(ESC).append(DELIM)
                '\n', '\r' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String): String {
        if (s.indexOf(ESC) < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ESC && i + 1 < s.length) {
                sb.append(s[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

package com.ake.llm

import com.ake.core.modelapi.OutputShape

/**
 * `OutputShape.AgentStep` 对应的 JSON Schema。
 *
 * **刻意写得宽松**（`additionalProperties: true`，只约束 `act` 必填）：
 *
 * 2B 量级模型配严格 schema 的常见失败不是「拒绝 Will fail 输出非法 JSON」，
 * 而是**卡住**：约束太紧会让它在 few-shot 例子没覆盖的字段上反复纠结，
 * decode 到 `maxOutputTokens` 才停下来 —— 单步从 2.3 秒变成十几秒。
 * 而我们对 `act` 之外字段的容忍度本来就很宽（`StepActionParser` 有完整容错 + `RepairHint`），
 * 所以约束交给后端要保证的是「一定产出 JSON」，字段合法性交给代码。
 */
public object AgentStepSchema {

    // ★ 不能是 `const val`：初始化式里调了 `trimIndent()`，不是编译期常量
    //   ⇒ "Const 'val' initializer must be a constant value"。
    //   改成 `val` 即可（它本来也只在需要构造 ResponseFormat 时才读一次）。
    public val VALUE: String = """
{
  "type": "object",
  "properties": {
    "act": { "type": "string" },
    "id": { "type": "integer" },
    "text": { "type": "string" },
    "submit": { "type": "boolean" },
    "value": { "type": "string" },
    "dir": { "type": "string" },
    "px": { "type": "integer" },
    "url": { "type": "string" },
    "selectorHint": { "type": "string" },
    "fields": { "type": "array", "items": { "type": "string" } },
    "limit": { "type": "integer" },
    "toolId": { "type": "string" },
    "args": { "type": "object" },
    "question": { "type": "string" },
    "answer": { "type": "string" },
    "evidenceRefs": { "type": "array", "items": { "type": "string" } }
  },
  "required": ["act"],
  "additionalProperties": true
}
""".trimIndent()
}

/** `OutputShape` → LiteRT-LM 的 `ResponseFormat`。`FreeText` 不加约束。 */
internal fun OutputShape.toResponseFormatOrNull(): com.google.ai.edge.litertlm.ResponseFormat? = when (this) {
    is OutputShape.JsonSchema -> com.google.ai.edge.litertlm.ResponseFormat.json(schema)
    OutputShape.AgentStep -> com.google.ai.edge.litertlm.ResponseFormat.json(AgentStepSchema.VALUE)
    OutputShape.FreeText -> null
}

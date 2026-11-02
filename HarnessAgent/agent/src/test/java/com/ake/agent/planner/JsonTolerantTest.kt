package com.ake.agent.planner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `JsonTolerant` 的容错边界。
 *
 * ## 为什么单独一个文件
 *
 * 这些用例与"动作语义"无关，只测**文本能不能被解析出来**。放在 `StepActionParserTest`
 * 里会被动作校验的逻辑盖住 —— 而它们失败时的症状（整份输出解析失败、模型被要求重试）
 * 与动作不合法完全不同，排查路径也不一样。
 */
class JsonTolerantTest {

    @Test
    fun parsesPrettyPrintedObject() {
        // ★ 回归用例：此前 normalize 用正则替换"裸换行"，把**结构里的换行**
        //   也替换成了字面的 `\n` 两个字符，导致美化过的 JSON 一律解析失败。
        val pretty = """
            {
              "steps": [
                {"do": "read_file"},
                {"do": "summarize"}
              ]
            }
        """.trimIndent()
        val obj = JsonTolerant.extractFirstObject(pretty)
        assertThat(obj).isNotNull()
        assertThat(obj!!.fields["steps"]?.asArray()).hasSize(2)
    }

    @Test
    fun keepsNewlineInsideStringAsEscapedChar() {
        // 字符串里的裸换行必须被转义，否则解析到换行就断了
        val raw = "{\"answer\":\"第一行\n第二行\"}"
        val obj = JsonTolerant.extractFirstObject(raw)
        assertThat(obj).isNotNull()
        assertThat(obj!!.fields["answer"]?.asString()).isEqualTo("第一行\n第二行")
    }

    @Test
    fun stillFixesTheUsualModelSloppiness() {
        // 围栏 + 单引号 + 未加引号的 key + 尾逗号，一次全给
        val raw = "```json\n{steps:[{'do':'read_file'},],}\n```"
        val obj = JsonTolerant.extractFirstObject(raw)
        assertThat(obj).isNotNull()
        assertThat(obj!!.fields["steps"]?.asArray()).hasSize(1)
    }
}

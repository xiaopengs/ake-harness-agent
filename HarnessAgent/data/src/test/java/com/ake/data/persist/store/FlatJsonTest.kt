package com.ake.data.persist.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `FlatJson` 的单测（2026-10）。
 *
 * ## 为什么它必须有测试
 *
 * 它是**新写的代码，且落在安全性相关的路径上**：
 *  - `mcp_server.headers_json` 里是每次请求都会带上的静态 Header；
 *  - `app_settings.allow_hosts_json` 是**域名白名单** —— 它直接决定 `TaskSpec.allowHosts`，
 *    也就是「Agent 被允许访问哪些域名」。
 *
 * 序列化错一次的症状不是「显示不对」，而是「白名单静默失效」或「白名单静默变成空」
 * （空集 = 不限，见 `LocalSettings.allowHosts` 的契约注释）。
 * 这两种失效方向里，后者会让护栏以为用户授权了所有域名 ——
 * 所以这里对「坏输入降级为空」这条宽容策略专门留了断言，防止它哪天被当成 bug「修掉」。
 */
class FlatJsonTest {

    // ──────────────────────────────────────────── 编码

    @Test
    fun `空集合有各自的规范写法`() {
        assertThat(FlatJson.encodeMap(emptyMap())).isEqualTo("{}")
        assertThat(FlatJson.encodeList(emptyList())).isEqualTo("[]")
    }

    @Test
    fun `Map 编码按键的字典序且可重复`() {
        val m = linkedMapOf("z" to "1", "a" to "2", "m" to "3")
        assertThat(FlatJson.encodeMap(m)).isEqualTo("""{"a":"2","m":"3","z":"1"}""")
        // 同一份输入两次编码必须逐字节相同（便于 diff 与「配置到底写成了什么」的排查）
        assertThat(FlatJson.encodeMap(m)).isEqualTo(FlatJson.encodeMap(m))
        // 插入顺序不同的两个 Map 编码结果相同
        assertThat(FlatJson.encodeMap(linkedMapOf("a" to "2", "m" to "3", "z" to "1")))
            .isEqualTo(FlatJson.encodeMap(m))
    }

    @Test
    fun `List 编码保序且不去重`() {
        assertThat(FlatJson.encodeList(listOf("b", "a", "b"))).isEqualTo("""["b","a","b"]""")
    }

    @Test
    fun `编码会转义四种字符且不转义非 ASCII`() {
        assertThat(FlatJson.encodeList(listOf("""a"b\c"""))).isEqualTo("""["a\"b\\c"]""")
        assertThat(FlatJson.encodeList(listOf("a\nb"))).isEqualTo("""["a\nb"]""")
        assertThat(FlatJson.encodeList(listOf("京东"))).isEqualTo("""["京东"]""")
    }

    // ──────────────────────────────────────────── 往返

    @Test
    fun `Header 往返一致`() {
        val m = mapOf("Authorization" to "Bearer abc.def", "X-Trace" to "on", "empty" to "")
        assertThat(FlatJson.decodeMap(FlatJson.encodeMap(m))).isEqualTo(m)
    }

    @Test
    fun `域名白名单往返一致`() {
        val hosts = listOf("example.com", "shop.example.com", "a-b.cn")
        assertThat(FlatJson.decodeList(FlatJson.encodeList(hosts))).isEqualTo(hosts)
    }

    @Test
    fun `含冒号斜杠与引号的值往返一致`() {
        // 值里含 `:` 是常见形态（Authorization / URL）；含 `"` 是最容易被写错的形态
        val m = mapOf("url" to "https://x.y/z?a=1", "q" to """say "hi"""")
        assertThat(FlatJson.decodeMap(FlatJson.encodeMap(m))).isEqualTo(m)
    }

    @Test
    fun `值里含大括号不会截断解析`() {
        val m = mapOf("tpl" to "{\"a\":1}", "k" to "v")
        assertThat(FlatJson.decodeMap(FlatJson.encodeMap(m))).isEqualTo(m)
    }

    @Test
    fun `值里含逗号与方括号不会截断列表解析`() {
        val items = listOf("a,b", "[x]", "c")
        assertThat(FlatJson.decodeList(FlatJson.encodeList(items))).isEqualTo(items)
    }

    // ──────────────────────────────────────────── 宽容解码

    @Test
    fun `坏输入降级为空集合而不是抛异常`() {
        // ★ 这些断言锁住的是**策略**，不是 bug：设置页不该因为一行脏数据打不开
        assertThat(FlatJson.decodeMap("")).isEmpty()
        assertThat(FlatJson.decodeMap("not json")).isEmpty()
        assertThat(FlatJson.decodeMap("{")).isEmpty()
        assertThat(FlatJson.decodeMap("""{"a"""")).isEmpty()
        assertThat(FlatJson.decodeList("")).isEmpty()
        assertThat(FlatJson.decodeList("[")).isEmpty()
        assertThat(FlatJson.decodeList("""["unterminated""")).isEmpty()
    }

    @Test
    fun `形状不匹配时返回空而不是猜`() {
        assertThat(FlatJson.decodeMap("[]")).isEmpty()
        assertThat(FlatJson.decodeList("{}")).isEmpty()
    }

    @Test
    fun `部分损坏时保留已经解析出来的部分`() {
        // 「宽容」的落点是「不整体失败」，而不是「要么全对要么全空」
        assertThat(FlatJson.decodeMap("""{"a":"1","b}""")).containsExactly("a", "1")
    }

    @Test
    fun `连花括号都没闭合时整体判为空`() {
        // 与上一条是两种情形：外层都不闭合时无法判定边界，这时宁可整体判空，
        // 也不要「猜一半」—— 猜一半会给出一份看起来合法、实际缺项的 Header。
        assertThat(FlatJson.decodeMap("""{"a":"1","broken""")).isEmpty()
    }

    @Test
    fun `裸键与尾逗号这类宽松写法也能读`() {
        // 与 planner/JsonTolerant 的降级方向一致，但**只**覆盖本文件自己编码不出来的两种形态
        assertThat(FlatJson.decodeList("""["a", "b" ,]""")).containsExactly("a", "b").inOrder()
        assertThat(FlatJson.decodeList("""[ "a" , "b" ]""")).containsExactly("a", "b").inOrder()
    }
}

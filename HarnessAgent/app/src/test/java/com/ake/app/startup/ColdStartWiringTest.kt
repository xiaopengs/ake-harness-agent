package com.ake.app.startup

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Test

/**
 * # 「调用点存在」的守卫（关千锤 P0-2 点名要的那条测试）
 *
 * ## 为什么要写这种看起来很丑的测试
 *
 * 上一轮的缺陷不是「实现错了」，而是「实现的**生产调用点为 0**」：
 * `TemplateBootstrap.bootstrap()` 与 `ChatLoop.onColdStart()` 各自有完整实现、
 * 各自有单测，全都过。可它们一次都没被跑过 —— 于是模板市场永久空白、
 * 存量人格永久「只回文字」、`STREAMING` 消息永久转圈。
 *
 * 这类缺陷**任何单元测试都抓不到**：单测按定义会自己调那个方法。
 * 能抓住它的只有两种手段 —— 「谁调它」的类型约束，或者「源码里到底有没有这一行」的扫描。
 * 前者在这个仓库里做不到（`:app` 是唯一的组合根，它调什么天然是自由的），
 * 所以只剩后者。它丑，但它是唯一能防止**下一次重构又把线剪了**的办法。
 *
 * ## 断的是什么
 *
 * | 断言 | 防止的事故 |
 * |---|---|
 * | `ColdStartCoordinator` 里出现 `TemplateBootstrap.bootstrap(` | 模板 / 授权回填被摘掉（市场空白 + 存量人格哑） |
 * | 同一文件里出现 `.onColdStart()` | `STREAMING` 收敛被摘掉（气泡永久转圈） |
 * | 该文件里有 `init {` 与 `launch {` | 退化成「需要别人记得调」的函数（那就又回到 0 调用点） |
 * | `AkeApp.kt` 里有 `coldStart.get()` | 单例从不被实例化 ⇒ `init` 自触发也不发生（接线被剪） |
 *
 * 扫描范围**只有 `:app` 的主源码**：这是「组合根」的定义域，业务实现不该在这里出现，
 * 所以这里出现 `TemplateBootstrap.` 就一定是接线而不是重复实现。
 */
class ColdStartWiringTest {

    @Test
    fun coordinatorCallsBothColdStartHooks() {
        val source = read(appMainSource().resolve("com/ake/app/startup/ColdStartCoordinator.kt"))

        assertThat(source).contains("TemplateBootstrap.bootstrap(")
        assertThat(source).contains(".onColdStart()")
    }

    @Test
    fun coordinatorSelfTriggersInsteadOfWaitingToBeCalled() {
        val source = read(appMainSource().resolve("com/ake/app/startup/ColdStartCoordinator.kt"))

        // 「自触发」= 构造函数一返回，工作就已经被 launch 出去了。
        // 少了这一条，本类就只是一个普通函数集合 ⇒ 又变成「等人来调」⇒ 迟早又变成 0 调用点。
        assertThat(source).contains("init {")
        assertThat(source).contains("launch {")
    }

    @Test
    fun applicationActuallyInstantiatesTheCoordinator() {
        val app = read(appMainSource().resolve("com/ake/app/AkeApp.kt"))

        // 单例的 init 自触发只有在**它被创建**时才发生。少了这一行，
        // ColdStartCoordinator 写得再对也永远不会被 Hilt 构造成实例。
        assertThat(app).contains("ColdStartCoordinator")
        assertThat(app).contains("coldStart.get()")
    }

    /**
     * 卡住「只写模板、不回填授权」这个真实发生过的半修。
     *
     * `bootstrap()` 与 `ensureBuiltins()` 的差别就是授权回填；如果有人把它换回
     * `ensureBuiltins()`（签名相近、看起来更"轻"），模板市场会好，而存量人格仍然哑 ——
     * 那是本轮最难被发现的一种「修了一半」。
     */
    @Test
    fun bootstrapIsTheFullEntryNotTheTemplateOnlyOne() {
        val source = read(appMainSource().resolve("com/ake/app/startup/ColdStartCoordinator.kt"))

        assertThat(source).contains("capabilityGrantDao")
        assertThat(source).doesNotContain("TemplateBootstrap.ensureBuiltins(")
    }

    // ─────────────────────────────────────── 源码定位

    /**
     * 定位 `:app` 的 `src/main/java`。
     *
     * 单测的工作目录依构建器不同可能是模块目录（AGP 的默认）或根目录，
     * 所以这里按候选顺序找，**找不到就大声失败** —— 静默跳过等于把守卫悄悄关掉，
     * 那正是本文件要防的那种事故。
     */
    private fun appMainSource(): Path {
        val candidates = listOf(
            "src/main/java",
            "app/src/main/java",
            "../app/src/main/java",
        )
        return candidates.asSequence()
            .map { Paths.get(it) }
            .firstOrNull { Files.isDirectory(it.resolve("com/ake/app")) }
            ?: throw AssertionError(
                "找不到 :app 的主源码目录（试过 $candidates，cwd=${Paths.get("").toAbsolutePath()}）。" +
                    "本测试靠源码扫描守住冷启接线，扫不到就等于没守。",
            )
    }

    private fun read(path: Path): String {
        if (!Files.exists(path)) throw AssertionError("接线文件不见了：$path")
        return path.toFile().readText()
    }
}

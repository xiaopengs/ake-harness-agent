package com.ake.ui.runtime

import com.ake.core.ports.CapabilityGrantPort
import com.ake.core.ports.ChatRunnerPort
import com.ake.core.ports.SkillRegistryPort
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 聊天功能的**可选依赖声明**。
 *
 * ## 这个模块解决的现实问题
 *
 * 截至本轮，`core:ports/Chat.kt` 的六个 Port 里，**只有三个在 Hilt 图里有绑定**
 * （全部来自 `:data` 的 `PersistenceModule`）：
 *
 * | Port | 实现 | Hilt 绑定 |
 * |---|---|---|
 * | `ChatSessionStorePort` | `:data` `ChatSessionStoreImpl` | ✅ `@Binds` |
 * | `AgentProfileStorePort` | `:data` `AgentProfileStoreImpl` | ✅ `@Binds` |
 * | `TemplateStorePort` | `:data` `TemplateStoreImpl` | ✅ `@Binds` |
 * | `ChatRunnerPort` | `:chat` `ChatLoop` | ❌ 只经 `ChatServices` 显式工厂，无 Hilt 绑定 |
 * | `SkillRegistryPort` | `:chat` `ChatSkillRegistry` | ❌ 同上 |
 * | `CapabilityGrantPort` | **全工程无实现**（`:data` 只有 `CapabilityGrantDao`） | ❌ |
 *
 * ## 为什么用 `@BindsOptionalOf` 而不是「要么硬注入、要么整个功能不写」
 *
 * 硬注入（`private val runner: ChatRunnerPort`）会让**整个 `:app` 编不过** ——
 * Dagger 在组合根校验图完整性，缺一个绑定就是 `[Dagger/MissingBinding]`。
 * 那等于 `:ui` 用一句「我先接上」把别人的构建打断，而缺的那个实现根本不在我这边。
 *
 * 反过来「整个功能不写」也不对：会话列表、消息流渲染、技能卡片、失败重试
 * 全都不依赖 `ChatRunnerPort` —— 它们依赖的是已绑定的三个 Store Port。
 * 为了一个没接的 Port 把它们一起推迟，是把「没接线」的成本放大到整个 UI。
 *
 * 所以这里声明**可选**：图里没有就注入 `Optional.empty()`，界面降级成
 * 「能看历史、发送按钮禁用并说明原因」；哪天 `:app` 把 `ChatRunnerPort` 绑上，
 * 同一个 `Optional` 自动变成 present，**`:ui` 一行都不用改**。
 *
 * ## 这**不是**在造实现
 *
 * 本文件没有任何 `ChatRunnerPort` 的具体类，也没有 fake / stub / mock。
 * 它只声明「这个绑定可能存在」—— 与 `@Binds` 的区别是：没有实现就不注入，
 * 而不是自己补一个空壳把编译糊过去。
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class ChatWiringModule {

    /** 见类注释。没有绑定时聊天页禁用发送并显式说明原因。 */
    @BindsOptionalOf
    public abstract fun optionalChatRunner(): ChatRunnerPort

    /** 见类注释。没有绑定时能力选择器只能显示已保存在人设里的能力引用。 */
    @BindsOptionalOf
    public abstract fun optionalSkillRegistry(): SkillRegistryPort

    /** 见类注释。没有绑定时能力勾选只改草稿、不落库，并在界面上说明。 */
    @BindsOptionalOf
    public abstract fun optionalCapabilityGrants(): CapabilityGrantPort
}

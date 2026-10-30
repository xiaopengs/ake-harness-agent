package com.ake.ui.runtime

import com.ake.core.ports.ModelRouter
import com.ake.ui.spi.CloudEndpointProbe
import com.ake.ui.spi.TurnEgressNoticeSource
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 云端推理能力的**可选依赖声明**。
 *
 * ## 与 `ChatWiringModule` 完全同一手法，理由也同一条
 *
 * 见该文件头部的表：`:ui` 需要的东西分布在 `:inference` / `:cloudai` / `:app`，
 * 组合根还没把它们绑上时，硬注入会让**整个 `:app` 编不过**（`[Dagger/MissingBinding]`）。
 * 那等于 `:ui` 用一句「我先接上」把别人的构建打断，而缺的实现根本不在这边。
 *
 * | Port | 今天有没有绑定 | 缺了会怎样（必须由界面说出来） |
 * |---|---|---|
 * | `CloudCredentialPort` | ✅ `:data` `PersistenceModule` | — |
 * | `DialogTargetStore` | ✅ `:data` `PersistenceModule` | — |
 * | `ModelRouter` | ❌ 实现在 `:inference` | 徽标退化为「登记的意图」，不是运行时事实 |
 * | `TurnEgressNoticeSource` | ❌ 全工程无实现（契约缺口） | **回落告知永远说不出来 ⇒ 静默降级窗口开着** |
 * | `CloudEndpointProbe` | ❌ §6.2 那个 probe 还不存在 | 自检显示「还没接上」，不用「能存进去」冒充「能连上」 |
 *
 * ## ★★ 后两行是合约缺口，不是待办 UI 活
 *
 * 它们俩都有 `:ui` 补不出来的部分：出境与回落的事实由推理层产生，
 * UI 无论怎么绕都不可能自己知道。本文件只声明「绑上来就自动生效」，
 * **不提供任何 fake 实现** —— 补一个空壳把编译糊过去，和静默降级是同一种病。
 */
@Module
@InstallIn(SingletonComponent::class)
public abstract class CloudWiringModule {

    /** 见类注释。未绑定时徽标退到「用户登记的意图」。 */
    @BindsOptionalOf
    public abstract fun optionalModelRouter(): ModelRouter

    /** 见类注释。未绑定时回落告知无从说起 —— 这是本轮最值得优先补的一条。 */
    @BindsOptionalOf
    public abstract fun optionalTurnEgressNoticeSource(): TurnEgressNoticeSource

    /** 见类注释。未绑定时连通性自检明说自己没接上。 */
    @BindsOptionalOf
    public abstract fun optionalCloudEndpointProbe(): CloudEndpointProbe
}

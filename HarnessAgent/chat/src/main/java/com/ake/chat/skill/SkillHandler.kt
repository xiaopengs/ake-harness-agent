package com.ake.chat.skill

import com.ake.core.ports.SkillCall
import com.ake.core.ports.SkillContext
import com.ake.core.ports.SkillEvent
import com.ake.core.ports.SkillSpec
import kotlinx.coroutines.flow.Flow

/**
 * # 一个技能 = 一份「写给模型看」的描述 + 一次「按参数干活」的执行
 *
 * 与 `:agent` 的 `ToolSource` / `CompositeToolRegistry` 刻意并列而**不**复用它们：
 *
 *  - `ToolRegistryPort` 是「ReAct 步进循环」的工具视图（`browser.click` / `type` / `extract` …），
 *    **每一步**都可能调它一次；
 *  - `SkillRegistryPort` 是「对话 Agent」的能力视图，**一次路由裁决**调它一次。
 *
 * 二者是同一批底层能力的两种视图，粒度不同、风险确认时机也不同（§5.1）。
 * 合成一个会让「聊天里点一下就执行」与「任务里第 7 步执行」共享同一套确认策略 ——
 * 而它们显然不该共享。
 */
public interface SkillHandler {

    /** ★ `description` 直接决定路由质量；它同时是 MCP / AppFunctions 暴露同一能力的**同一份文本**。 */
    public val spec: SkillSpec

    /** 执行。**返回冷 Flow，最后一条必须是 `Succeeded` 或 `Failed`**（与 `TaskEvent` 同源纪律）。 */
    public suspend fun invoke(call: SkillCall, ctx: SkillContext): Flow<SkillEvent>
}

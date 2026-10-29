package com.ake.inference

import com.ake.core.common.Outcome
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.SessionSpec
import com.ake.core.ports.DeviceModelPort
import com.ake.core.ports.DialogModelPort
import com.ake.core.ports.EgressPermit
import com.ake.core.ports.ModelCapabilities
import com.ake.core.ports.PromptContext

/**
 * ★ 把端侧引擎「升格」成对话入口（架构 08 §2.4）。
 *
 * 它的全部意义是把 `DeviceModelPort`（`LiteRtModelPort`）包装成
 * `DialogModelPort`，于是 `:chat` 只认一个形状 —— 调用点上
 * 「本地路径」与「云端路径」长得一模一样。
 *
 * ## ★ `permit` 与 `context` 在这里被**忽略，但签名上必须在场**
 *
 *  · `permit`  —— 本地推理不需要出境许可；但**形参仍在**，
 *    否则调用点就要写 `if (target is Remote)` 这种运行时分支。
 *  · `context` —— `WithPageContext` 在端侧是**合法**的
 *    （浏览器任务本来就走 `DeviceModelPort`），因此这里不做拒绝。
 *    ★ 拒绝只在云端实现里做（§5.4 锁 C）。
 */
public class LocalDialogAdapter(
    private val device: DeviceModelPort,
) : DialogModelPort {

    override val capabilities: ModelCapabilities get() = device.capabilities

    override fun estimateTokens(text: String): Int = device.estimateTokens(text)

    override suspend fun load(config: com.ake.core.modelapi.ModelLoadConfig): Outcome<Unit> =
        device.load(config)

    override suspend fun unload(): Outcome<Unit> = device.unload()

    override val runtimeState: kotlinx.coroutines.flow.Flow<com.ake.core.modelapi.ModelRuntimeState>
        get() = device.runtimeState

    override suspend fun openSession(spec: SessionSpec): Outcome<ModelSession> = device.openSession(spec)

    override suspend fun openDialog(
        spec: SessionSpec,
        permit: EgressPermit?,
        context: PromptContext,
    ): Outcome<ModelSession> = device.openSession(spec)
}

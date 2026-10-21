package com.ake.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ThinkingConfig
import com.ake.core.modelapi.InferenceBackend
import com.ake.core.modelapi.ThinkingBudget

/**
 * 本项目类型 ⇄ LiteRT-LM 类型的唯一转换点。
 *
 * **为什么必须集中在一处**：R5 要求 `Backend` / `Conversation` / `ThinkingConfig` 这些名字
 * 不得出现在 L4/L5/L6 的任何文件里。集中成一个文件之后，**换推理引擎 = 改这一个文件**。
 *
 * 所有签名均来自 **litertlm-android:0.18.0 的真实 class 文件**（javap 反编译核实），非记忆推测。
 */
// ★ `npuLibraryDir` 是**非空** String：`Backend.NPU(nativeLibraryDir: String)` 不接受 null，
//   而调用方（LiteRtModelPort.createEngine）永远能给出具体目录
//   （`config.npuLibraryDir ?: applicationInfo.nativeLibraryDir`），
//   把这个「可能为空」留在签名里只会把 null 一路传进 native 层。
internal fun InferenceBackend.toLiteRt(npuLibraryDir: String): Backend = when (this) {
    InferenceBackend.CPU -> Backend.CPU()
    InferenceBackend.GPU -> Backend.GPU()
    InferenceBackend.NPU -> Backend.NPU(npuLibraryDir)
    InferenceBackend.GOOGLE_TENSOR -> Backend.GOOGLE_TENSOR()
}

internal fun ThinkingBudget.toLiteRt(): ThinkingConfig = when (this) {
    ThinkingBudget.Off -> ThinkingConfig()
    is ThinkingBudget.Capped -> ThinkingConfig(enableThinking = true, thinkingTokenBudget = tokens)
}

internal val InferenceBackend.label: String get() = name

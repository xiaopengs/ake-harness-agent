package com.ake.agent.planner

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Outcome
import com.ake.core.common.Severity
import com.ake.core.common.StepId
import com.ake.core.contract.PlanStep
import com.ake.core.contract.PlanStepKind
import com.ake.core.modelapi.FinishReason
import com.ake.core.modelapi.GenerationRequest
import com.ake.core.modelapi.ModelPort
import com.ake.core.modelapi.ModelSession
import com.ake.core.modelapi.OutputShape
import com.ake.core.modelapi.SessionSpec
import com.ake.core.modelapi.ThinkingBudget
import com.ake.core.ports.DEFAULT_MODEL_ID
import com.ake.core.ports.DocProbeResult
import com.ake.core.ports.DocumentPort
import com.ake.core.ports.PageBudget
import com.ake.core.ports.PageEvent
import com.ake.document.ChunkAssembler
import com.ake.document.PageSlice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 五类计划步骤的真实实现（`read_file` / `pdf_extract` / `summarize` / `report_todos`）。
 *
 * ## 为什么 `export_records` 不在这里
 *
 * 导出需要落盘（`FileAccessPort` 的写方向），而 `:agent` 拿不到 Android 的存储上下文。
 * 硬在这里 `File(...).writeText` 会写出一个 App 私有目录里的文件，用户在"文件"里找不到 ——
 * 那比"这一步没实现"更糟。**明确留缺**，由 `:app` 用一个写文件的 handler 补上。
 *
 * ## 摘要为什么一定要 map-reduce
 *
 * 50 页 PDF 的正文约 2.5 万字 = 8k+ token，而 6GB 机型的可用 prompt 预算只有 3–4k。
 * 一次性塞进去的结果不是"摘要质量差"，是**KV cache 直接溢出**（要么报
 * `CONTEXT_OVERFLOW`，要么 native 层杀进程）。所以先分块各摘一遍，再对摘要做合并。
 */
public object PlanStepHandlers {

    public fun routing(
        documents: DocumentPort,
        model: ModelPort,
        assembler: ChunkAssembler = ChunkAssembler(),
    ): PlanStepHandler = KindRoutedPlanStepHandler(
        mapOf(
            PlanStepKind.READ_FILE to ReadFileStepHandler(documents),
            PlanStepKind.EXTRACT to ExtractStepHandler(documents, assembler),
            PlanStepKind.SUMMARIZE to SummarizeStepHandler(model),
            PlanStepKind.TODOS to TodosStepHandler(model),
        ),
    )
}

/** 摘要分块按字符切，不按 token：token 估算要过模型，切块时不该付这个成本。 */
private const val MAP_CHUNK_CHARS: Int = 2_000

/** map 阶段的单块摘要上限。给多了模型会把原文抄一遍。 */
private const val MAP_MAX_TOKENS: Int = 220

/** reduce 阶段输出上限。5 条要点 + 一句总述，320 够。 */
private const val REDUCE_MAX_TOKENS: Int = 320

/**
 * `read_file`：确认文件能读，并把"这是什么"告诉下一步。
 *
 * 它**不读正文** —— 正文是 `pdf_extract` 的活。这一步的价值是把"加密 / 扫描件 / 太大"
 * 这三类失败提前到第 1 步暴露：用户在第 1 步看到"这是扫描件，抽不出文字"，
 * 比跑完 4 步再告诉他"结果为空"要好得多。
 */
public class ReadFileStepHandler(
    private val documents: DocumentPort,
) : PlanStepHandler {

    override suspend fun execute(step: PlanStep, ctx: PlanStepContext): Outcome<PlanStepOutput> {
        val source = ctx.document
            ?: return Outcome.Err(
                AppError("NO_DOCUMENT", "err_plan_no_document", severity = Severity.MEDIUM),
            )

        return when (val probe = documents.probe(source)) {
            is DocProbeResult.Failed -> Outcome.Err(probe.error.toAppError())
            is DocProbeResult.Ok -> {
                val p = probe.profile
                val kind = if (p.isTextual) "文本型" else "扫描件"
                Outcome.Ok(
                    PlanStepOutput(
                        text = "《${source.displayName}》共 ${p.pageCount} 页，$kind，平均 ${p.avgCharsPerPage} 字/页。",
                    ),
                )
            }
        }
    }
}

/**
 * `pdf_extract`：逐页抽正文 → 分块 → 拼成一份**有上限**的文本交给下一步。
 *
 * 上限来自 [ChunkAssembler] 的 12 块硬约束（约 9k token）。超过的部分**丢弃并记录**
 * `truncatedFromPage`：悄悄截断会让摘要在"第 30 页之后"凭空漏掉内容，
 * 而这个事实必须能追到（日志 + UI 提示都靠它）。
 */
public class ExtractStepHandler(
    private val documents: DocumentPort,
    private val assembler: ChunkAssembler = ChunkAssembler(),
    private val budget: PageBudget = PageBudget.DEFAULT,
) : PlanStepHandler {

    override suspend fun execute(step: PlanStep, ctx: PlanStepContext): Outcome<PlanStepOutput> {
        val source = ctx.document
            ?: return Outcome.Err(AppError("NO_DOCUMENT", "err_plan_no_document", severity = Severity.MEDIUM))

        val slices = ArrayList<PageSlice>(budget.maxPages)
        var skipped = 0

        try {
            // 逐页收集到内存列表是**有意的**：`ChunkAssembler` 要按页边界合并，
            // 而它需要一个完整列表。内存上界由 `maxPages × maxCharsPerPage` 保证（≈4MB）。
            val events: List<PageEvent> = documents.pages(source, budget).toList()
            for (e in events) {
                when (e) {
                    is PageEvent.Text -> slices += PageSlice(e.page, e.content)
                    is PageEvent.Skipped -> skipped++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Outcome.Err(AppError.wrap(e))
        }

        if (slices.isEmpty()) {
            return Outcome.Err(
                AppError("DOC_NO_TEXT", "err_doc_no_text", severity = Severity.MEDIUM),
            )
        }

        val doc = assembler.assemble(slices)
        val text = doc.chunks.joinToString("\n\n") { it.text }
        val note = buildString {
            append("已抽取 ${slices.size} 页")
            if (skipped > 0) append("（跳过 $skipped 页）")
            if (doc.isTruncated) append("；超出 12 块上限，从第 ${doc.truncatedFromPage} 页起未纳入")
            append("。")
        }
        AkeLog.i(TAG, note)
        return Outcome.Ok(PlanStepOutput(text = note + "\n\n" + text))
    }

    private companion object {
        const val TAG: String = "ExtractStep"
    }
}

/**
 * `summarize`：map-reduce。
 *
 * map 阶段**每次开新会话**（不是复用同一个 session）：50 页 = 6–7 块，
 * 复用会让 KV cache 一路涨到溢出；而每次新建只多付一次 system prompt 的 prefill
 * （约 120 token），比溢出后重跑便宜得多。
 */
public class SummarizeStepHandler(
    private val model: ModelPort,
    private val modelId: String = DEFAULT_MODEL_ID,
) : PlanStepHandler {

    override suspend fun execute(step: PlanStep, ctx: PlanStepContext): Outcome<PlanStepOutput> {
        if (ctx.upstream.isBlank()) {
            return Outcome.Err(AppError("EMPTY_INPUT", "err_plan_empty_input", severity = Severity.MEDIUM))
        }

        val chunks = splitForMap(ctx.upstream)
        val partials = ArrayList<String>(chunks.size)

        for ((i, chunk) in chunks.withIndex()) {
            if (ctx.abort.isAborted()) {
                return Outcome.Err(AppError.interrupted())
            }
            val prompt = buildString {
                appendLine("下面是文档的第 ${i + 1}/${chunks.size} 段。用 3 句话概括它的要点。")
                appendLine("只输出这 3 句话，不要重复原文，不要加标题。")
                appendLine()
                append(chunk)
            }
            val got = call(ctx, prompt, MAP_MAX_TOKENS)
            if (got is Outcome.Err) return got
            partials += (got as Outcome.Ok).value
        }

        if (partials.size == 1) {
            return Outcome.Ok(PlanStepOutput(partials.single()))
        }

        val reducePrompt = buildString {
            appendLine("下面是同一份文档各段的摘要。把它们合并成 5 条以内的要点，")
            appendLine("用「- 」开头的列表输出，不要写「综上所述」之类的套话。")
            appendLine()
            partials.forEachIndexed { i, p -> appendLine("[$i] $p") }
        }
        val reduced = call(ctx, reducePrompt, REDUCE_MAX_TOKENS)
        return when (reduced) {
            is Outcome.Err -> reduced
            is Outcome.Ok -> Outcome.Ok(PlanStepOutput(reduced.value))
        }
    }

    /** 按段落边界切，单段超长再硬切。 */
    private fun splitForMap(text: String): List<String> {
        if (text.length <= MAP_CHUNK_CHARS) return listOf(text)
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (line in text.lineSequence()) {
            if (buf.length + line.length + 1 > MAP_CHUNK_CHARS && buf.isNotEmpty()) {
                out += buf.toString()
                buf.clear()
            }
            if (line.length > MAP_CHUNK_CHARS) {
                var rest = line
                while (rest.length > MAP_CHUNK_CHARS) {
                    out += rest.take(MAP_CHUNK_CHARS)
                    rest = rest.drop(MAP_CHUNK_CHARS)
                }
                buf.appendLine(rest)
            } else {
                buf.appendLine(line)
            }
        }
        if (buf.isNotBlank()) out += buf.toString()
        return out
    }

    private suspend fun call(ctx: PlanStepContext, prompt: String, maxTokens: Int): Outcome<String> =
        callModel(model, modelId, ctx.taskId.value, prompt, maxTokens)
}

/**
 * `report_todos`：从上游内容里抽待办。
 *
 * 明确要求"没有就写没有"：2B 模型在被要求"找出待办"时，倾向于把普通陈述
 * 编成待办（"应加强管理" → "- [ ] 加强管理"）。一份全是编造待办的清单，
 * 用户看两次就不会再用了。
 */
public class TodosStepHandler(
    private val model: ModelPort,
    private val modelId: String = DEFAULT_MODEL_ID,
) : PlanStepHandler {

    override suspend fun execute(step: PlanStep, ctx: PlanStepContext): Outcome<PlanStepOutput> {
        if (ctx.upstream.isBlank()) {
            return Outcome.Err(AppError("EMPTY_INPUT", "err_plan_empty_input", severity = Severity.MEDIUM))
        }
        val prompt = buildString {
            appendLine("从下面的内容里抽出**明确要求去做**的事项，用「- [ ] 」开头的清单输出。")
            appendLine("只写内容里真的写着要做的事。一条都没有就只输出：未发现明确待办。")
            appendLine("不要写总结、不要解释、不要编号。")
            appendLine()
            append(ctx.upstream.take(TODOS_INPUT_CHARS))
        }
        return when (val r = callModel(model, modelId, ctx.taskId.value, prompt, TODOS_MAX_TOKENS)) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(PlanStepOutput(r.value))
        }
    }

    private companion object {
        const val TODOS_MAX_TOKENS: Int = 320
        /** 待办抽取只看前面一段：真要抽全，应该按块抽再合并（下一版）。 */
        const val TODOS_INPUT_CHARS: Int = 6_000
    }
}

/** 单次生成：开会话 → 生成 → 关会话。失败一律折成 `Outcome.Err`，不抛。 */
internal suspend fun callModel(
    model: ModelPort,
    modelId: String,
    taskId: String,
    prompt: String,
    maxTokens: Int,
    timeoutMs: Long = 60_000,
): Outcome<String> {
    val opened = model.openSession(
        SessionSpec(
            modelId = modelId,
            taskId = taskId,
            maxOutputTokens = maxTokens,
            temperature = 0.2,
            thinking = ThinkingBudget.Off,
            enableJsonConstraint = false,
        ),
    )
    if (opened is Outcome.Err) return opened

    val session: ModelSession = (opened as Outcome.Ok).value
    return try {
        val generated = withTimeoutOrNull(timeoutMs) {
            session.generate(
                GenerationRequest(
                    stepId = StepId.new(),
                    userTurn = prompt,
                    outputShape = OutputShape.FreeText,
                    maxOutputTokens = maxTokens,
                    thinking = ThinkingBudget.Off,
                    deadlineMs = timeoutMs,
                ),
            )
        } ?: return Outcome.Err(AppError.timeout("plan_step_generate"))

        when (generated) {
            is Outcome.Err -> generated
            is Outcome.Ok -> {
                if (generated.value.finishReason == FinishReason.ERROR) {
                    Outcome.Err(AppError("MODEL_GENERATE_FAILED", "err_model_generate_failed", severity = Severity.MEDIUM))
                } else {
                    Outcome.Ok(generated.value.rawText.trim())
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Outcome.Err(AppError.wrap(e))
    } finally {
        runCatching { session.close() }
    }
}

/** 文档错误 → 应用错误。`reasonKey` 是 UI 文案的 key，不在这里拼中文。 */
internal fun com.ake.core.ports.DocumentError.toAppError(): AppError = when (this) {
    is com.ake.core.ports.DocumentError.Encrypted ->
        AppError("DOC_ENCRYPTED", "err_doc_encrypted", severity = Severity.MEDIUM)

    is com.ake.core.ports.DocumentError.NotTextual ->
        AppError("DOC_NOT_TEXTUAL", "err_doc_not_textual", severity = Severity.MEDIUM)

    is com.ake.core.ports.DocumentError.TooLarge ->
        AppError("DOC_TOO_LARGE", "err_doc_too_large", severity = Severity.MEDIUM)

    is com.ake.core.ports.DocumentError.PageFailed ->
        AppError("DOC_PAGE_FAILED", "err_doc_page_failed")

    is com.ake.core.ports.DocumentError.Io ->
        AppError("DOC_IO", "err_doc_io", severity = Severity.MEDIUM)
}

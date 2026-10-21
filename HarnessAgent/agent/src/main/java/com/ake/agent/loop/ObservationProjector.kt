package com.ake.agent.loop

import com.ake.core.common.Clock
import com.ake.core.common.Redactor
import com.ake.core.common.StepId
import com.ake.core.common.TaskId
import com.ake.core.common.Ulid
import com.ake.core.contract.ObservationSummary
import com.ake.core.contract.PageState
import com.ake.core.contract.PayloadState
import com.ake.core.contract.PocketDom
import com.ake.core.contract.RetentionClass
import com.ake.core.contract.decodeSignalFlags
import com.ake.core.contract.toSignalFlags
import com.ake.core.contract.toWarningFlags

/**
 * `PocketDom` → `ObservationSummary` 的**显式投影**（数据库 §3.1 铁律）。
 *
 * ★ 持久化层**绝不许直接收下 `PocketDom`** —— 那样「随手把整个 PocketDom 塞进库」
 * 在编译期就不可行。必须走这个投影，于是「到底哪些字段进了库」这个问题
 * 有唯一一处答案，改字段时也不会有第二处忘了同步。
 *
 * 另一个同等重要的原因：`PocketDom` 是**给模型看的形态**（文本、编号、Top-K），
 * 而 `ObservationSummary` 是**给人与外部查询看的形态**（位掩码、计数、host 明文）。
 * 这两者的演进方向完全不同，混在一个类型里迟早被某一方拖着变形。
 */
object ObservationProjector {

    fun project(
        dom: PocketDom,
        taskId: TaskId,
        stepId: StepId?,
        seq: Int,
        clock: Clock,
        /** 序列化后的完整快照（给 payload 文件用）。为 null 表示只留元数据。 */
        payload: String?,
        /** 采集点已经做过的脱敏命中数（数据库 §8.3(e) 自证）。 */
        redactionCount: Int,
        redactionTypes: List<String>,
    ): ObservationSummary {
        val storedBytes = payload?.toByteArray(Charsets.UTF_8)?.size ?: 0
        return ObservationSummary(
            id = Ulid.next(clock.nowMillis()),
            taskId = taskId,
            stepId = stepId,
            seq = seq,
            createdAt = clock.nowMillis(),
            // host 明文是刻意的：域名策略要精确等值查询，加密会破坏这件事（数据库 §8.1）
            host = dom.host.ifBlank { hostOf(dom.url) },
            urlPath = pathOf(dom.url),
            titleRedacted = redactTitle(dom.title),
            pageState = dom.pageState.name,
            warningFlags = dom.warnings.toWarningFlags(),
            signalFlags = dom.signals.toSignalFlags(),
            candidateCount = dom.candidates.size,
            recordRowCount = dom.records?.rows?.size ?: 0,
            rawNodeCount = dom.stats.rawNodeCount,
            prunedNodeCount = dom.stats.prunedNodeCount,
            serializedChars = dom.stats.serializedChars,
            pageDigest = dom.digest.value,
            redactionCount = redactionCount,
            redactionTypes = redactionTypes,
            payloadCodec = if (payload == null) null else "gzip",
            // 文件名由 :data:persist 的 PayloadStore 决定，这里只声明意图
            payloadFile = null,
            payloadRawBytes = storedBytes,
            payloadStoredBytes = storedBytes,
            payloadState = if (payload == null) PayloadState.DROPPED else PayloadState.EXTERNAL,
        )
    }

    /** 与 `:agent:context/UrlSanitizer.hostOf` 同口径（小写、去 www.、去端口、去 userinfo）。 */
    fun hostOf(url: String): String {
        val s = url.substringAfter("://", url)
        return s.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfter('@').substringBefore(':')
            .lowercase().removePrefix("www.")
    }

    /**
     * ★ 页面标题脱敏（R0）。**`titleRedacted` 这一列必须真的脱敏过。**
     *
     * 这里原本只做了 `take(128)` —— 于是「订单 6225 **** 3210 已提交」这类标题会原样落库，
     * 并一路流到历史列表与通知栏。列名里写着 `Redacted`，内容却是明文，
     * 这比「没有这一列」更危险：**它会让后来的人以为这一列是安全的。**
     *
     * 走 `Redactor.scrub()`（R0 凭据规则）而不是 `PolicyPort.sanitize()`（闸 1 注入规则）：
     * 标题里的风险是「带着卡号/验证码」，不是「试图给模型下指令」。
     * 用错闸口的代价不对称 —— 用闸 1 会让带可疑措辞的正常标题整条消失，
     * 而漏掉 R0 是实打实的凭据落盘。
     *
     * @return `null` 表示无标题（ blanks 也归为无，避免库里存一堆空字符串）。
     */
    fun redactTitle(raw: String): String? {
        val cut = raw.trim().take(TITLE_MAX)
        if (cut.isBlank()) return null
        return Redactor.scrub(cut).text.ifBlank { null }
    }

    /** path 单独留一列：域名策略查 host，排查问题要看 path，二者不能合并。 */
    fun pathOf(url: String): String {
        val s = url.substringAfter("://", url)
        val noAuthority = s.substringAfter('/', "")
        return ("/" + noAuthority.substringBefore('#')).take(512)
    }

    /** 页面是否处于「任务不可继续」状态（架构 §5.2 / 宪章第 6 条：不绕过风控）。 */
    fun isDeadEnd(state: PageState): Boolean =
        state == PageState.BLOCKED || state == PageState.CAPTCHA || state == PageState.LOGIN_WALL

    /** 保留分级：MVP 全部 FULL，降级由 `:data` 的 RetentionJob 决定（数据库 §4.4）。 */
    const val DEFAULT_RETENTION: String = RetentionClass.FULL

    /** 标题落库上限。标题是给人扫一眼的，不是给回放用的，超了只会撑大行。 */
    const val TITLE_MAX: Int = 128
}

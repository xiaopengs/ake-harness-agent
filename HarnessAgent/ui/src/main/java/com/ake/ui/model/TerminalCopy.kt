package com.ake.ui.model

import com.ake.agent.loop.TerminalReason

/**
 * 十二终态 → 人话。
 *
 * ## 为什么这张表必须在 UI 里完整穷尽
 *
 * 架构 §3.5 的十二终态里，有相当一部分是**「模型自己不知道自己错了」**的情形：
 * `FABRICATED_ANSWER`（编造答案）、`NO_PROGRESS`（原地打转）、`OSCILLATION`（来回横跳）。
 * 这些词如果原样丢给用户，用户只会看到「任务结束」，然后照着一份不可信的结果去行动 ——
 * 那比直接失败更糟。所以每个终态都必须有：①一句话结论 ②为什么会这样 ③下一步该做什么。
 *
 * 映射写成 `when` **而不是 map**：`TerminalReason` 是封闭枚举，
 * 用 `when` 才能在同事新增第 13 个终态时让编译直接失败，而不是悄悄落到某个「未知错误」文案上。
 */
public data class TerminalCopy(
    /** 一句话结论，进标题栏。 */
    val title: String,
    /** 发生了什么。讲人话，不出现内部代号。 */
    val what: String,
    /** 用户下一步能做什么。 */
    val nextStep: String,
    /**
     * 结果是否可信。
     *
     * ★ 这是本表最重要的一个字段：`FABRICATED_ANSWER` 与 `INFEASIBLE` 在 status 上同属
     * `IMPOSSIBLE`，但前者意味着「模型给了一段看起来像答案、实际无法从页面溯源的文本」——
     * UI 必须在结果卡片顶部挂一条不可关闭的警示，否则用户会把编造当事实。
     */
    val trust: Trust,
    /** 是否显示「继续」入口（仅当存在可用断点）。 */
    val resumableHint: Boolean = false,
) {
    public enum class Trust { TRUSTED, UNTRUSTED, NONE }
}

public fun TerminalReason.copy(): TerminalCopy = when (this) {

    // ── ① 真正完成 ──────────────────────────────────────────────
    TerminalReason.COMPLETED -> TerminalCopy(
        title = "已完成",
        what = "目标达成，并且答案里的每一条都能在当前页面上找到出处 —— 不是模型说做完了就算完。",
        nextStep = "直接看结果；需要保留就复制走，不需要就让它随保留策略自然过期。",
        trust = TerminalCopy.Trust.TRUSTED,
    )

    // ── ② 做不到 ────────────────────────────────────────────────
    TerminalReason.INFEASIBLE -> TerminalCopy(
        title = "这件事做不了",
        what = "页面把它挡住了：可能是需要登录、出现了验证码，或者站点判定本次访问异常。" +
            "本 App 明确不去绕过这些风控 —— 那是替用户做越界的事。",
        nextStep = "先在内置浏览器里手动登录一次再重试；如果站点持续拦截，说明这条路线本身不通，换个目标。",
        trust = TerminalCopy.Trust.NONE,
    )

    // ── ③ ★ 编造答案 —— 全表最危险的一项 ────────────────────────
    TerminalReason.FABRICATED_ANSWER -> TerminalCopy(
        title = "结果不可信：答案很可能是编的",
        what = "模型报了「完成」，但答案里的内容在这轮页面上找不到出处。" +
            "端侧小模型在上下文被挤爆或页面结构异常时，会填出一段通顺但没有根据的文本 —— " +
            "它自己不知道自己在编。系统在这里把它拦下来了。",
        nextStep = "不要采用这次的结果。把目标拆小一点、或指定更明确的页面再跑一次；" +
            "如果反复出现，说明当前档位的上下文预算对这个页面不够用。",
        trust = TerminalCopy.Trust.UNTRUSTED,
        resumableHint = true,
    )

    // ── ④ 用户中止 ──────────────────────────────────────────────
    TerminalReason.USER_ABORT -> TerminalCopy(
        title = "已停止",
        what = "你（或系统里的中止信号）让它停下了。这是正常行为，不是出错。",
        nextStep = "如果只是改主意，直接重新下达任务；如果想从停下的地方接上，用「继续」。",
        trust = TerminalCopy.Trust.NONE,
        resumableHint = true,
    )

    // ── ⑤ 电量 ──────────────────────────────────────────────────
    TerminalReason.BATTERY_STOP -> TerminalCopy(
        title = "电量不足，已保存进度后停止",
        what = "电量低于 8% 且没在充电。端侧推理是满载跑的，继续下去大概率是跑到一半关机，" +
            "所以这里先落盘断点再停。",
        nextStep = "充上电后可以从断点继续，已做完的步骤不会白做。",
        trust = TerminalCopy.Trust.NONE,
        resumableHint = true,
    )

    // ── ⑥ 预算耗尽 ──────────────────────────────────────────────
    TerminalReason.BUDGET_EXHAUSTED -> TerminalCopy(
        title = "预算用完了",
        what = "步数或 token 用到了上限。端侧单步要 4.5 秒左右，预算卡在这里是为了不让一次任务" +
            "把电和内存吃干 —— 它按的是「花掉的钱」而不是步数，所以复杂的页面会更早触顶。",
        nextStep = "把目标拆成两段跑（先取列表、再取详情），或在设置里调高档位。",
        trust = TerminalCopy.Trust.NONE,
    )

    // ── ⑦ 超时 ──────────────────────────────────────────────────
    TerminalReason.TIMEOUT -> TerminalCopy(
        title = "超时了",
        what = "总时长超过了档位上限。常见原因是页面一直没稳定下来（无限滚动、动画没停）。",
        nextStep = "换个更具体的入口网址再跑；这类页面本来就难稳定。",
        trust = TerminalCopy.Trust.NONE,
        resumableHint = true,
    )

    // ── ⑧ 循环 ──────────────────────────────────────────────────
    TerminalReason.LOOP_DETECTED -> TerminalCopy(
        title = "卡在重复动作上了",
        what = "同一个动作连续出现了 3 次。典型场景是点了一个没反应的按钮，或者页面每次都把它弹回去。",
        nextStep = "在任务描述里换个说法指明另一条路径（比如「直接打开搜索结果页」而不是「点搜索」）。",
        trust = TerminalCopy.Trust.NONE,
    )

    // ── ⑨ 无进展 ────────────────────────────────────────────────
    TerminalReason.NO_PROGRESS -> TerminalCopy(
        title = "一直在原地打转",
        what = "连续 4 步页面结构、地址、抓到的数据都没变。多数是点了不生效的元素，或掉进了无限加载。",
        nextStep = "先把目标改成「确认页面上有没有 XX」这类只读任务，确认 Agent 能看见页面再谈操作。",
        trust = TerminalCopy.Trust.NONE,
    )

    // ── ⑩ 来回横跳 ──────────────────────────────────────────────
    TerminalReason.OSCILLATION -> TerminalCopy(
        title = "在两个页面之间来回跳",
        what = "访问路径出现了 A → B → A 这样的环。模型在找某个信息，但每次回来又走同一条路。",
        nextStep = "把要找的东西说得更具体（给出栏目名或页面特征），别让它自己猜路。",
        trust = TerminalCopy.Trust.NONE,
    )

    // ── ⑪ 定位失败 ──────────────────────────────────────────────
    TerminalReason.LOCATE_FAILED -> TerminalCopy(
        title = "找不到要操作的元素",
        what = "同一个目标连续定位失败。页面每次局部重绘都会让元素编号漂移，" +
            "这里已经降级过一次（改用抽取而不是点击），仍然失败才熔断。",
        nextStep = "这个页面大概率是高度动态的 SPA。换个页面，或把任务改成只读抽取。",
        trust = TerminalCopy.Trust.NONE,
        resumableHint = true,
    )

    // ── ⑫ 输出修不回来 ──────────────────────────────────────────
    TerminalReason.REPAIR_FAILED -> TerminalCopy(
        title = "模型输出连续两次读不懂",
        what = "连续两步的输出都没法解析成动作。端侧小模型在上下文被挤爆时会先输出格式崩坏的文本，" +
            "这里给了它一次修正机会，仍然不行就停。",
        nextStep = "重试一次通常就能过。如果稳定复现，说明当前页面的上下文占用过高，需要调低候选数量。",
        trust = TerminalCopy.Trust.NONE,
        resumableHint = true,
    )
}

/**
 * 护栏 reasonKey → 人话。
 *
 * key 取自 `agent/guard/PolicyEngine` 与 `SensitiveActionPolicy` 的实际取值，
 * 新增 key 会落到最后的兜底文案（这里是有意为之：未知 key 的兜底必须是「更保守的措辞」，
 * 而不是「看起来没问题的措辞」）。
 */
public fun confirmCopyOf(reasonKey: String, args: List<String>): String = when (reasonKey) {
    "confirm.cross_domain" ->
        "这个动作会离开当前站点，去到 ${args.firstOrNull() ?: "另一个域名"}。" +
            "我们是带着你的登录态在访问网页的，跨站意味着把登录态带到一个新的地方。"

    "confirm.submit_form" ->
        "即将提交表单${args.firstOrNull()?.let { "（${it.take(30)}）" } ?: ""}。提交后会发生什么取决于站点本身，通常不可撤销。"

    "confirm.submit_financial" ->
        "这个表单看起来涉及银行卡 / 支付 / 发票信息。提交前请确认这是你要做的事。"

    "confirm.login" ->
        "即将执行一次登录动作。登录会把你的账号与这个站点的一次会话绑定。"

    "confirm.add_to_cart" ->
        "即将把商品加入购物车。这是真实站点上的真实操作，不是模拟。"

    "confirm.download" ->
        "即将触发一次下载。下载会落到本机存储，也会在你的账号里留下记录。"

    "confirm.send_message" ->
        "即将发送内容（评论 / 私信 / 回复）。发出去就收不回了，且是以你的身份发出。"

    "confirm.mcp_first_use" ->
        "即将调用外部工具 ${args.firstOrNull() ?: ""}。这一步是你自己接入的 MCP 工具，" +
            "它可能在你本机或局域网里产生副作用。"

    "confirm.account_modify" ->
        "即将修改账号资料或绑定关系。这类改动在多数站点上很难撤销。"

    "confirm.data_delete" ->
        "即将删除站点上的数据。删除通常不可恢复。"

    else -> "这个动作被安全策略判定为需要你确认。如果不确定它在做什么，选「拒绝」最安全。"
}

/** 拒绝 / 硬拒 reasonKey → 人话（展示在步骤流的「被拦下」标签上）。 */
public fun denialCopyOf(reasonKey: String?): String = when (reasonKey) {
    "err.forbidden_action" -> "被安全策略硬拒：这类动作（支付 / 下单 / 自动填充凭据 / 上传证件）一律不提供确认选项"
    "err.domain_blacklisted" -> "目标域名在黑名单里，已拒绝访问"
    "err.credential_in_url" -> "链接里带着凭据类参数，已拒绝跳转（防止登录态外泄）"
    "err.credential_field" -> "目标输入框看起来是密码 / 验证码 / 卡号，禁止自动输入"
    "err.credential_text" -> "要输入的内容看起来是凭据，已拒绝"
    "err.insecure_scheme" -> "非 https 链接，已拒绝（我们带着登录态访问，不接受明文跳转）"
    "err.bad_scheme" -> "链接格式不合法"
    "err.element_not_found" -> "这一轮页面上找不到目标元素（编号只对当前这一轮有效）"
    "err.tool_disabled" -> "这个工具已被你停用"
    "err.input_too_long" -> "输入内容过长，已拒绝"
    "err.limit_too_large" -> "抽取条数超出上限，已拒绝"
    "err.user_rejected" -> "你拒绝了这一步"
    "err.gate_timeout" -> "等待确认超时，已按拒绝处理"
    else -> "被安全策略拦下"
}

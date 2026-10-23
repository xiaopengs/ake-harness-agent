package com.ake.data.persist.modeldist

/**
 * 下载完成后的校验与失败处置（架构 A2：「中断 10 次后仍能完成下载」的另一半）。
 *
 * ## 为什么 sha256 必校（不是可选优化）
 *
 * 2.6 GB 在移动网络上静默损坏的概率不算低，而**损坏的模型不崩溃** ——
 * 它表现为「能加载、能出字，但输出偶发乱码 / 工具调用格式随机错」。
 * 这类故障在线上几乎无法归因（用户只会说「最近有点笨」），
 * 定位成本远高于下完顺手算一次摘要。
 *
 * ## 为什么 sha256 不匹配必须**重下**而不是续传
 *
 * 续传的前提是「已落盘的字节是对的」。摘要不匹配说明前面某个字节已经错了，
 * 而 HTTP Range 续传只保证「从第 N 字节继续」，不会回头修前面的错 ——
 * 接着传等于在一个已知错误的前缀上继续追加，再怎么重试都是同一个坏结果。
 * 所以：不匹配 ⇒ 丢弃 `.part` ⇒ 从头再来。
 */
public enum class VerifyVerdict {
    /** 字节数与摘要都对 ⇒ 可以发布为可用模型 */
    MATCH,

    /** 字节数对不上：传输被截断或服务端换了文件 */
    SIZE_MISMATCH,

    /** 字节数对得上、摘要不对：静默损坏 */
    HASH_MISMATCH,

    /** 磁盘上根本没有文件（被清理工具删了 / 换卡） */
    FILE_MISSING,
}

public enum class VerifyAction {
    /** 校验通过：`.part` 转正 */
    PROMOTE,

    /** 丢弃 `.part` 从头再下（**不**删任何已装模型） */
    DISCARD_AND_RESTART,

    /** 重试次数耗尽：标记 CORRUPTED 并交给上层提示，不再自己重试 */
    GIVE_UP,
}

public object ModelVerifyGate {

    /** 与 `StrictJson` 里那条 64-hex 规则同源，独立留一份是因为校验侧也要独立判一次。 */
    private val SHA256_HEX: Regex = Regex("^[0-9a-fA-F]{64}$")

    public fun isSha256Hex(value: String): Boolean = SHA256_HEX.matches(value)

    /**
     * @param actualBytes 磁盘上文件的字节数；`null` 表示文件不存在
     * @param actualSha256 流式算出的摘要；`null` 表示没算出来（等价于没文件）
     */
    public fun evaluate(
        entry: ModelManifestEntry,
        actualBytes: Long?,
        actualSha256: String?,
    ): VerifyVerdict {
        if (actualBytes == null) return VerifyVerdict.FILE_MISSING
        if (actualBytes != entry.sizeBytes) return VerifyVerdict.SIZE_MISMATCH
        if (actualSha256 == null) return VerifyVerdict.HASH_MISMATCH
        return if (actualSha256.equals(entry.sha256, ignoreCase = true)) {
            VerifyVerdict.MATCH
        } else {
            VerifyVerdict.HASH_MISMATCH
        }
    }

    /**
     * @param attemptsDone **含本次**已尝试次数（调用方传 `row.verifyAttempts + 1`）
     */
    public fun nextAction(verdict: VerifyVerdict, attemptsDone: Int, maxAttempts: Int): VerifyAction = when (verdict) {
        VerifyVerdict.MATCH -> VerifyAction.PROMOTE
        VerifyVerdict.FILE_MISSING -> VerifyAction.DISCARD_AND_RESTART // 没有可丢的，等价于从 0 开始
        VerifyVerdict.SIZE_MISMATCH, VerifyVerdict.HASH_MISMATCH ->
            if (attemptsDone >= maxAttempts) VerifyAction.GIVE_UP else VerifyAction.DISCARD_AND_RESTART
    }
}

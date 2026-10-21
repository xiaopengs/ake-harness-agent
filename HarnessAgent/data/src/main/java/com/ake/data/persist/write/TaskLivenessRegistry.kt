package com.ake.data.persist.write

import java.util.concurrent.ConcurrentHashMap

/**
 * 本进程「正在跑 task」的存活登记表（进程内有效，不落盘）。
 *
 * ## 为什么必须有它
 *
 * 对账员要判断「这条 RUNNING 行到底是还活着，还是孤儿？」。
 * 单靠 `last_activity_at` 不够：一次合法的长时间停顿（模型加载 10 s、
 * 用户切后台、磁盘满载写不进去）都可能让心跳变旧。**误杀一个活任务是比留一个孤儿更糟的失败**
 * —— 前者用户会眼睁睁看着任务被打断，后者只是历史列表多一条。
 *
 * 所以判定用**两个条件的合取**：
 *
 * ```
 * 孤儿 ⟺ 不在存活登记表（本进程没有人在跑它） 且 心跳已过期
 * ```
 *
 * 冷启动时登记表必然是空的 ⇒ 每一条 RUNNING 行都是孤儿（进程死了，循环不可能还活着）。
 * 这也让它「不需要任何额外的 Bool持久化」：进程一死，表里的一切自动归零，
 * 而这正好是我们想要的对账起点。
 */
public class TaskLivenessRegistry {

    /** taskId → 最近一次心跳的时间戳（毫秒）。只用于观测，不参与判定。 */
    private val live = ConcurrentHashMap<String, Long>()

    public fun markLive(taskId: String, atMillis: Long) {
        live[taskId] = atMillis
    }

    public fun markNotLive(taskId: String) {
        live.remove(taskId)
    }

    public fun isLive(taskId: String): Boolean = live.containsKey(taskId)

    public fun liveCount(): Int = live.size

    /** 观测口径：给起搏检查与调试面板。 */
    public fun snapshot(): Set<String> = live.keys.toSet()
}

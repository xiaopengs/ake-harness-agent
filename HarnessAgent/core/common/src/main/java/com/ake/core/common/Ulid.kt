package com.ake.core.common

import java.security.SecureRandom
import java.util.Random

/**
 * ULID 生成器。
 *
 * **为什么主键必须是 ULID 而不是自增 RowId / 随机 UUID v4**（数据库 §6 写入性能）：
 *
 * 我们的轨迹写入是**重追加、几乎不更新**的形态（每步 10.6 行）。B-tree 主键如果是随机的，
 * 每次 insert 都会命中随机的叶子页 ⇒ 页分裂 + 随机写放大 + WAL 暴涨。
 * ULID 的前 48 bit 是毫秒时间戳，**单调递增 ⇒ 插入永远命中 B-tree 最右侧叶子**，
 * 这把「随机写」变成「顺序追加」，是在移动端 SQLite 上能拿到的最大一笔写入优化。
 *
 * 另一个同样重要的性质：**它的时间序可以在「拿到 id 的那一刻」就确定，不需要先落库。**
 * L4 可以在把一个任务放进队列之前就定好 taskId/stepId，且 id 之间的先后关系与真实发生顺序一致。
 *
 * 单调性负责 avoiding「同一毫秒内乱序」，代价是同一毫秒内的多个 id 并非密码学独立随机 ——
 * 这在我们的场景里是正确的取舍（id 不需要抗猜，只需要有序）。
 */
public object Ulid {

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford Base32
    private const val TIME_CHARS = 10          // 48 bit 毫秒
    private const val RAND_CHARS = 16          // 80 bit 随机
    private const val RAND_BYTES = 10          // 80 bit

    private val SECURE: Random = SecureRandom()

    private val lock = Any()
    private var lastMillis: Long = -1L
    private var lastRandom: ByteArray = ByteArray(RAND_BYTES)

    /** 生成一个 ULID。同毫秒内递增，保证单调。 */
    public fun next(nowMillis: Long = System.currentTimeMillis()): String {
        val randomBits = synchronized(lock) {
            if (nowMillis == lastMillis) {
                increment(lastRandom)
            } else {
                lastMillis = nowMillis
                lastRandom = ByteArray(RAND_BYTES).also { SECURE.nextBytes(it) }
            }
            lastRandom.copyOf()
        }
        return encode(nowMillis, randomBits)
    }

    /** 测试/回放专用：固定随机源，使黄金轨迹可重现。 */
    public fun next(nowMillis: Long, random: Random): String =
        encode(nowMillis, ByteArray(RAND_BYTES).also { random.nextBytes(it) })

    /** 从 ULID 反解毫秒时间戳，用于「按时间范围查」与单测断言。 */
    public fun parseTimeMillis(ulid: String): Long {
        require(ulid.length == TIME_CHARS + RAND_CHARS) { "not a ULID: $ulid" }
        var t = 0L
        for (i in 0 until TIME_CHARS) {
            val d = ALPHABET.indexOf(ulid[i].uppercaseChar())
            require(d >= 0) { "bad char '${ulid[i]}'" }
            t = (t shl 5) or d.toLong()
        }
        return t
    }

    public fun isValid(value: String): Boolean {
        if (value.length != TIME_CHARS + RAND_CHARS) return false
        return value.all { it.uppercaseChar() in ALPHABET }
    }

    private fun encode(millis: Long, randomBits: ByteArray): String {
        val sb = StringBuilder(TIME_CHARS + RAND_CHARS)
        // 时间：写成 50 bit 数（高 2 bit 必为 0），取 10 个 base32 字符
        val timeBits = TIME_CHARS * 5
        repeat(TIME_CHARS) { i ->
            var acc = 0
            for (b in 0 until 5) {
                val shift = timeBits - 1 - (i * 5 + b)
                acc = (acc shl 1) or (if (shift in 0..62) ((millis shr shift) and 1L).toInt() else 0)
            }
            sb.append(ALPHABET[acc])
        }
        // 随机：80 bit 精确铺满 16 个字符
        repeat(RAND_CHARS) { i ->
            var acc = 0
            for (b in 0 until 5) {
                val bitIndex = i * 5 + b
                val byte = randomBits[bitIndex / 8].toInt()
                acc = (acc shl 1) or ((byte shr (7 - bitIndex % 8)) and 1)
            }
            sb.append(ALPHABET[acc])
        }
        return sb.toString()
    }

    /** 大端 +1，溢出则回绕（同一毫秒内生成 2^80 个 id 才会发生，届时接受回绕）。 */
    private fun increment(bits: ByteArray) {
        for (i in bits.indices.reversed()) {
            val v = (bits[i].toInt() and 0xFF) + 1
            bits[i] = (v and 0xFF).toByte()
            if (v and 0xFF != 0) return
        }
    }
}

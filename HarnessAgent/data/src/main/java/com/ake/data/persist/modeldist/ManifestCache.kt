package com.ake.data.persist.modeldist

import com.ake.core.common.AkeLog
import java.io.File

/**
 * manifest 的**磁盘缓存**。
 *
 * ## 为什么必须落盘（而不是只在内存里留着）
 *
 * 缓存的目的是「**断网 / 服务端挂了**的时候端上仍有一个已验签的 manifest」。
 * 只放内存的话，冷启动 + 断网 = 完全没有任何 manifest ⇒ 用户连已装好的模型都用不了。
 * 那恰好是 manifest 方案最该兜住的场景（回滚要的就是「不开网也能切」）。
 *
 * ## 为什么缓的是**原文**而不是解析后的对象
 *
 * 1. **缓对象就要给 `ModelManifest` 做序列化**，而序列化格式一旦随字段演进漂移，
 *    老缓存反序列化失败 ⇒ 又退回「没有 manifest」；缓原文则永远能被当前解析器重新校验。
 * 2. **原文带签名** ⇒ 每次读缓存都**重新验一遍签**（见 `ModelManifestSource`）。
 *    缓存文件本身是普通文件，别的进程/清理工具动过手的可能是存在的，
 *    「读缓存不再验签」等于给本地篡改开了一道口子。
 *
 * ## 为什么写要原子（tmp + rename）
 *
 * 写一半被杀进程是最常见的中断（用户对 2.6 GB 的下载随时可能切走 App）。
 * 直接写目标文件会留下一个截断的 JSON ⇒ 下次冷启动解析失败 ⇒ 表现是
 * 「重启后模型突然不能用了」。rename 在同一文件系统上是原子的，代价只是一个临时文件。
 */
public class ManifestCache(
    private val dir: File,
) {

    public fun read(): String? {
        val f = File(dir, FILE_NAME)
        if (!f.isFile) return null
        return runCatching { f.readText(Charsets.UTF_8) }
            .onFailure { AkeLog.w(TAG, "cache read failed: ${it.message}") }
            .getOrNull()
    }

    /** 原子写入。返回 `false` 表示没写进去（目录不可写 / 空间不足）—— 调用方按「无缓存」处理。 */
    public fun write(raw: String): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) {
            AkeLog.w(TAG, "cache dir unavailable: ${dir.absolutePath}")
            return false
        }
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, TMP_FILE_NAME)
        return runCatching {
            tmp.writeText(raw, Charsets.UTF_8)
            // ★ rename 失败不能吞：吞了就会留下一个永不生效的新 manifest，
            //   而调用方以为已经缓存成功 ⇒ 下次断网时其实什么都没有。
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@runCatching false
            }
            true
        }.onFailure { AkeLog.w(TAG, "cache write failed: ${it.message}") }.getOrDefault(false)
    }

    public fun clear() {
        runCatching { File(dir, FILE_NAME).delete() }
        runCatching { File(dir, TMP_FILE_NAME).delete() }
    }

    public companion object {
        public const val FILE_NAME: String = "manifest.json"
        public const val TMP_FILE_NAME: String = "manifest.json.tmp"

        private const val TAG: String = "ManifestCache"
    }
}

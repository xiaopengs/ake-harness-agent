package com.ake.core.ports

/**
 * 文件访问 Port（交付方案 §3.4 / §5.3）。
 *
 * ## 为什么入参是 `uri: String` 而不是 `android.net.Uri`
 *
 * 与 [DocumentPort] 同一条理由：`:core:ports` 是纯 JVM。Uri 的解析、权限校验、
 * 元信息查询全部在 `:app` 的实现里；契约层只承诺"给一个字符串，还一个本地文件"。
 *
 * ## 为什么 `openRead` 返回的是本地路径而不是 fd
 *
 * PDFBox 需要可随机访问、生命周期稳定的 `File`。SAF 的 `ParcelFileDescriptor`
 * 在部分 provider（云盘、压缩包内文件）上不可 seek，`/proc/self/fd/N` 也不能直接
 * 交给 PDFBox 的 `load(File)`。所以 V1 统一落盘到 `doc-cache/`，
 * 差异只在"任务结束后是否保留"——由 [FileHandle.retainAfterTask] 表达。
 *
 * ⚠ 这是对交付方案"≤20MB 直接吃 fd"的**有意偏离**：那条路只在本地文件 provider
 * 上成立，云盘 provider 会退化成"每次重下"。统一落盘换来的是行为一致，
 * 代价是首开多一次拷贝（50MB 约 0.4–1.2s【待实测】）。
 */
public sealed interface FileAccessError {
    /** 用户撤销了授权，或设备重启后持久授权失效。 */
    public data object PermissionRevoked : FileAccessError

    public data object NotFound : FileAccessError

    /** 超过产品上限（默认 200MB）。 */
    public data class TooLarge(public val limitBytes: Long) : FileAccessError

    public data class Io(public val reason: String) : FileAccessError
}

public data class FileHandle(
    /** 已落盘的本地绝对路径。 */
    public val localPath: String,
    public val displayName: String,
    public val byteSize: Long,
    /** true = 用完不必删（小文件留在 LRU 缓存里）；false = 任务结束应清理。 */
    public val retainAfterTask: Boolean,
)

public data class FileMeta(
    public val displayName: String,
    public val byteSize: Long?,
    public val mimeType: String?,
)

public sealed interface FileAccessResult {
    public data class Ok(public val handle: FileHandle) : FileAccessResult
    public data class Failed(public val error: FileAccessError) : FileAccessResult
}

public interface FileAccessPort {
    /**
     * 拿到可读的本地文件。实现必须：
     * ① 先查缓存（同一 uri 不重复拷贝）；
     * ② 校验持久授权，失效时返回 [FileAccessError.PermissionRevoked] 而不是抛异常；
     * ③ 拷贝完成后比对字节数，截断的副本要删掉重来。
     */
    public suspend fun openRead(uri: String): FileAccessResult

    /**
     * 把一次性读授权升级为跨重启的持久授权。
     * 返回 false 表示 provider 不支持（如部分第三方文件管理器），调用方应降级为"本次会话可用"。
     */
    public suspend fun persistGrant(uri: String): Boolean

    public suspend fun meta(uri: String): FileMeta?

    /** 任务结束调用：按 [FileHandle.retainAfterTask] 决定删除或保留。 */
    public suspend fun release(handle: FileHandle)

    /** LRU 修剪，返回删除的条目数。冷启动与每次写入后调用。 */
    public suspend fun trimCache(): Int
}

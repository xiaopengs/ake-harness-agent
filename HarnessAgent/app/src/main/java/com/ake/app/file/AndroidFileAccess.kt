package com.ake.app.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.ake.core.ports.FileAccessError
import com.ake.core.ports.FileAccessPort
import com.ake.core.ports.FileAccessResult
import com.ake.core.ports.FileHandle
import com.ake.core.ports.FileMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 实现（`:app` 层，因为它必须持有 ContentResolver）。
 *
 * ## 三个必须处理的现实
 *
 * ① **持久授权会失效**：设备重启、用户在系统设置里撤销、provider 卸载，都会让
 *    `persistedUriPermissions` 里查不到这个 URI。此时不能抛 `SecurityException`
 *    给上层，要返回 [FileAccessError.PermissionRevoked]，让 UI 引导重选 —— 这是
 *    线上最高频的文件类故障。
 *
 * ② **字节数不可信**：部分 provider 的 `OpenableColumns.SIZE` 为空或滞后。
 *    拷贝完必须 `File.length()` 复核；不一致就删掉重来，绝不把截断文件交给 PDFBox
 *    （截断 PDF 的症状是"解析到一半崩"，比直接失败更难排查）。
 *
 * ③ **缓存必须能重建**：缓存键是 uri 的 sha256，命中也只信文件本身（长度 >0）。
 *    缓存目录被系统清理时不能报错，直接重拷即可。
 *
 * ## 与交付方案的偏离
 *
 * 方案写"≤20MB 直接吃 fd"，这里统一落盘。原因见 `FileAccessPort` 头部：
 * 云盘 provider 的 fd 不可 seek，PDFBox 需要稳定 File。阈值退化为
 * "任务后是否保留"，由 [DocCachePolicy.retainAfterTask] 决定。
 */
@Singleton
public class AndroidFileAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : FileAccessPort {

    private val io: CoroutineDispatcher = Dispatchers.IO

    private val cacheDir: File
        get() = File(context.filesDir, "doc-cache").apply { if (!exists()) mkdirs() }

    override suspend fun openRead(uri: String): FileAccessResult = withContext(io) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            ?: return@withContext FileAccessResult.Failed(FileAccessError.NotFound)

        val cached = File(cacheDir, DocCachePolicy.keyOf(uri) + ".doc")
        if (cached.isFile && cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis())   // LRU 的 lastAccess
            return@withContext FileAccessResult.Ok(
                FileHandle(
                    localPath = cached.absolutePath,
                    displayName = cached.name,
                    byteSize = cached.length(),
                    retainAfterTask = DocCachePolicy.retainAfterTask(cached.length()),
                ),
            )
        }

        val meta = meta(uri)
        val declared = meta?.byteSize ?: 0L
        if (declared > DocCachePolicy.MAX_FILE_BYTES) {
            return@withContext FileAccessResult.Failed(
                FileAccessError.TooLarge(DocCachePolicy.MAX_FILE_BYTES),
            )
        }

        val tmp = File(cacheDir, cached.name + ".part")
        try {
            val copied = context.contentResolver.openInputStream(parsed)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            } ?: return@withContext FileAccessResult.Failed(FileAccessError.NotFound)

            // ② 复核真实字节数：provider 报的 size 可能为空/滞后。
            if (tmp.length() <= 0 || (declared > 0 && tmp.length() != declared)) {
                tmp.delete()
                return@withContext FileAccessResult.Failed(
                    FileAccessError.Io("文件不完整（声明 $declared 字节，实收 ${tmp.length()}）"),
                )
            }
            if (!tmp.renameTo(cached)) {
                tmp.delete()
                return@withContext FileAccessResult.Failed(FileAccessError.Io("缓存写入失败"))
            }
            FileAccessResult.Ok(
                FileHandle(
                    localPath = cached.absolutePath,
                    displayName = meta?.displayName ?: cached.name,
                    byteSize = cached.length(),
                    retainAfterTask = DocCachePolicy.retainAfterTask(cached.length()),
                ),
            )
        } catch (e: SecurityException) {
            tmp.delete()
            FileAccessResult.Failed(FileAccessError.PermissionRevoked)
        } catch (e: Exception) {
            tmp.delete()
            FileAccessResult.Failed(FileAccessError.Io(e.message ?: e::class.java.simpleName))
        }
    }

    override suspend fun persistGrant(uri: String): Boolean = withContext(io) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext false
        try {
            context.contentResolver.takePersistableUriPermission(
                parsed,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (e: SecurityException) {
            // provider 没声明 FLAG_GRANT_PERSISTABLE_URI_PERMISSION：不是错误，
            // 只是跨重启不可用。返回 false 让调用方降级，不打断当前会话。
            false
        }
    }

    override suspend fun meta(uri: String): FileMeta? = withContext(io) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext null
        runCatching {
            context.contentResolver.query(
                parsed,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                FileMeta(
                    displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else "document",
                    byteSize = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        null
                    },
                    mimeType = context.contentResolver.getType(parsed),
                )
            }
        }.getOrNull()
    }

    override suspend fun release(handle: FileHandle) = withContext(io) {
        if (handle.retainAfterTask) return@withContext
        runCatching { File(handle.localPath).delete() }
        Unit
    }

    override suspend fun trimCache(): Int = withContext(io) {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.name.endsWith(".doc") }.orEmpty()
        if (files.isEmpty()) return@withContext 0

        val entries = files.map {
            CacheEntry(key = it.name, bytes = it.length(), lastAccessAt = it.lastModified())
        }
        val doomed = DocCachePolicy.evict(entries).toSet()
        var removed = 0
        files.forEach { file ->
            if (file.name in doomed && file.delete()) removed++
        }
        // 顺手清掉上次进程被杀留下的半截文件
        cacheDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        removed
    }
}

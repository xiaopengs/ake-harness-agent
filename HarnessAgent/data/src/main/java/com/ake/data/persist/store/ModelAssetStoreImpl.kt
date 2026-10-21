package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.contract.ModelAssetRecord
import com.ake.core.contract.ModelAssetState
import com.ake.core.ports.ModelAssetStorePort
import com.ake.data.persist.db.AkeDatabase
import com.ake.data.persist.db.mapper.EntityMappers

/**
 * `ModelAssetStorePort` 实现（数据库 F4 / A2）。
 *
 * A2 验收项「中断 10 次后仍能完成下载；损坏文件自动清理」在**跨 App 重启**的前提下
 * 必须有持久化的 `bytes_downloaded` 与 `state` —— 这是这张表存在的唯一理由。
 *
 * 与轨迹表相反，这里**不走写入队列**：下载进度是用户可见的状态机，
 * 写丢了会让「已下载 1.8 GB / 2.58 GB」跳回 0，那是不能接受的。
 * 低频（一次下载几千次写，但都在后台线程）+ 必须成功 ⇒ 直接 DAO。
 */
public class ModelAssetStoreImpl(
    private val db: AkeDatabase,
) : ModelAssetStorePort {

    override suspend fun currentReady(): ModelAssetRecord? =
        runCatching { db.modelAssetDao().currentReady()?.let(EntityMappers::modelAssetToDomain) }
            .onFailure { AkeLog.w(TAG, "currentReady failed: ${it.message}") }
            .getOrNull()

    suspend fun byVariant(modelId: String, variant: String): ModelAssetRecord? =
        runCatching { db.modelAssetDao().byVariant(modelId, variant)?.let(EntityMappers::modelAssetToDomain) }
            .getOrNull()

    fun observeReady() = db.modelAssetDao().observeReady()

    suspend fun markDownloading(id: String, bytes: Long) {
        db.modelAssetDao().updateProgress(id, bytes, ModelAssetState.DOWNLOADING)
    }

    suspend fun markVerifying(id: String) {
        db.modelAssetDao().updateProgress(id, 0, ModelAssetState.VERIFYING)
    }

    /** 孤儿 `*.part` 清理的查询入口（§4.5 纪律 3）。 */
    suspend fun pendingTempFiles(): List<String> =
        runCatching { db.modelAssetDao().pendingTempFiles() }.getOrDefault(emptyList())

    companion object {
        const val TAG: String = "ModelAssetStore"
    }
}

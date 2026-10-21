package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.ModelAssetEntity
import kotlinx.coroutines.flow.Flow

/**
 * 模型资产表（数据库 F4 / A2：下载状态机的落点）。
 *
 * 业务键是 `(model_id, variant)`，主键是 ULID —— 因为下载记录要跨 App 重启保留，
 * 而同一个 variant 可能因为文件损坏被删了重下，用业务键做主键会丢掉「上次为什么坏」的证据。
 */
@Dao
interface ModelAssetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: ModelAssetEntity)

    @Query("SELECT * FROM model_asset WHERE model_id = :modelId AND variant = :variant LIMIT 1")
    suspend fun byVariant(modelId: String, variant: String): ModelAssetEntity?

    @Query("SELECT * FROM model_asset WHERE state = 'READY' ORDER BY last_used_at DESC LIMIT 1")
    suspend fun currentReady(): ModelAssetEntity?

    @Query("SELECT * FROM model_asset WHERE state = 'READY'")
    fun observeReady(): Flow<List<ModelAssetEntity>>

    @Query("SELECT * FROM model_asset ORDER BY download_started_at DESC")
    fun all(): Flow<List<ModelAssetEntity>>

    /** ★ Range 续传的生命线：只更新进度，不动其它列。 */
    @Query("UPDATE model_asset SET bytes_downloaded = :bytes, state = :state WHERE id = :id")
    suspend fun updateProgress(id: String, bytes: Long, state: String)

    @Query(
        """
        UPDATE model_asset
           SET state = :state, local_path = :localPath, temp_file = :tempFile,
               verified_at = :at, actual_bytes = :actualBytes, actual_sha256 = :actualSha,
               verify_attempts = verify_attempts + 1, verify_last_result = :result
         WHERE id = :id
        """,
    )
    suspend fun finishVerify(
        id: String,
        state: String,
        localPath: String?,
        tempFile: String?,
        at: Long,
        actualBytes: Long?,
        actualSha: String?,
        result: String?,
    )

    @Query("UPDATE model_asset SET last_error = :error, last_error_at = :at WHERE id = :id")
    suspend fun setError(id: String, error: String?, at: Long)

    @Query(
        """
        UPDATE model_asset
           SET last_used_at = :at, load_count = load_count + 1,
               last_backend = :backend, last_load_ms = :loadMs
         WHERE id = :id
        """,
    )
    suspend fun markLoaded(id: String, at: Long, backend: String?, loadMs: Long?)

    @Query("DELETE FROM model_asset WHERE id = :id")
    suspend fun delete(id: String)

    /** 孤儿 `*.part` 清理：找出所有停留在 DOWNLOADING/PAUSED 的临时文件名。 */
    @Query("SELECT temp_file FROM model_asset WHERE temp_file IS NOT NULL AND state <> 'READY'")
    suspend fun pendingTempFiles(): List<String>
}

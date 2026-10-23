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

    // ────────────────────────────────────────────────────────────────────────
    // 模型分发（manifest 驱动）新增的两条查询（霍承钧 · P1）
    //
    // 为什么必须新增，而不是复用 `currentReady()`：
    //  `currentReady()` 是 `WHERE state='READY' ORDER BY last_used_at DESC LIMIT 1` ——
    //  它回答的是「现在该加载哪一个」，而**回滚**要回答的是「盘上一共有哪几个可用版本」。
    //  只凭「最近用过的一个」做回滚，会在「新版本已下完、旧版本还留着」的情况下
    //  看不见旧版本 ⇒ 回滚退化成重新下载 2.6 GB，那正好否定了 manifest 方案的意义。
    // ────────────────────────────────────────────────────────────────────────

    /** 按主键取一行（写路径需要读到 `verify_attempts` 才能决定重试还是放弃）。 */
    @Query("SELECT * FROM model_asset WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ModelAssetEntity?

    /** 全量快照（含归档行）。回滚候选集与「绝不删除」审计都靠它。 */
    @Query("SELECT * FROM model_asset ORDER BY download_finished_at DESC, id ASC")
    suspend fun allOnce(): List<ModelAssetEntity>
}

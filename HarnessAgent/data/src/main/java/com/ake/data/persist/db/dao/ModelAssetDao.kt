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

    // ──────────────────────────────────────────────────────────────────────
    // 归档清理扫描（`ArchiveRetentionSweep`）用的两条
    // ──────────────────────────────────────────────────────────────────────

    /** 按 modelId 取仅供排重的 modelId 全集（扫描粒度，`ArchiveRetentionSweep` 用）。 */
    @Query("SELECT DISTINCT model_id FROM model_asset")
    suspend fun distinctModelIds(): List<String>

    /**
     * 摘掉一行上的 `.part` 指针。**带 CAS 守卫**（`AND temp_file = :name`）。
     *
     * @return 实际更新的行数。`0` = 这一行在「扫描」与「动手」之间已被别的写者改过
     *   （一次并发 `beginOrResume` 换了新名字 / 已经自己清掉了）⇒ 调用方必须放弃本次动作，
     *   否则会把别人刚写的临时文件当成旧垃圾删掉。
     *   这与 `TaskReconciler` 那两条 `UPDATE … AND status = ?` 是同一套幂等手法。
     */
    @Query("UPDATE model_asset SET temp_file = NULL WHERE id = :id AND temp_file = :name")
    suspend fun clearTempFile(id: String, name: String): Int

    /**
     * [clearTempFile] 的逆操作：**摘了指针却没删掉文件时把指针还回去**。
     *
     * 为什么必须有它：指针已空而文件仍在 ⇒ 这份 `.part` 从此没有任何行指向它，
     * 既不会被扫描再发现，也不会被下次 `beginOrResume` 的 `Discard` 点到 ——
     * 一个永久孤儿，而永久孤儿正是这次要对付的东西。
     *
     * 为什么带 `AND temp_file IS NULL` 守卫：只有在我们自己刚摘掉、且没被别人改写的情况下
     * 才写回；若另一个写者已经用上了这一行，就不该由我们把老名字塞回去。
     */
    @Query("UPDATE model_asset SET temp_file = :name WHERE id = :id AND temp_file IS NULL")
    suspend fun restoreTempFile(id: String, name: String): Int

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

    // ────────────────────────────────────────────────────────────────────────
    // 按 modelId 列出**全部**行（霍承钧 · 交接项 B2）
    //
    // 起因：删除路径上的命名在两侧不一致 ——
    //   `:llm` 的 `ModelDownloader.delete(id)` 删的是 `<id>.litertlm`，
    //   而 `ModelLifecyclePort.deleteModelFile(modelId)` 传进去的是 **modelId**；
    //   账本里真正在盘上的文件名是 `<assetId>.litertlm`（assetId = ULID 主键，
    //   与 modelId 只在旧数据里碰巧相同）。
    //   ⇒ 只按 modelId 删一个文件，会留下槽位文件、归档件与 .part 全部不动，
    //     用户以为删掉了 2.6 GB，实际一个字节都没少。
    //
    // 所以这里补的是**查询**而不是「把命名改回去」：
    //   `:llm` 拿到某个 modelId 下的每一行后即可逐个算出它拥有的全部文件名
    //   （见 `ModelAssetLedger.ownedFileNames`）。
    //   改命名规则的代价是现有盘上文件全部失联，那条路不能走。
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 某个 modelId 下的**全部**行：槽位行 + 归档行 + 残缺行。
     * 归档行的 `variant` 是 `<variant>@<sha12>`，但 `model_id` 列原样保留，
     * 所以这一条就能把「一个模型的全部版本」捞齐。
     */
    @Query("SELECT * FROM model_asset WHERE model_id = :modelId ORDER BY download_finished_at DESC, id ASC")
    suspend fun byModelId(modelId: String): List<ModelAssetEntity>
}

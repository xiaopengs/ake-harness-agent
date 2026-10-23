package com.ake.data.persist.fake

import com.ake.data.persist.db.dao.ModelAssetDao
import com.ake.data.persist.db.entity.ModelAssetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * `ModelAssetDao` 的内存实现。
 *
 * 与 `FakeTaskDao` 同一条纪律：**未被本轮测试触达的路径一律抛异常**，
 * 而不是静默返回一个「看起来对」的值 —— 静默返回值会让后来者以为那条路径已被覆盖。
 *
 * 这里刻意**把 SQL 的守卫语义搬进内存**的三条（`ModelAssetLedger` 依赖的正是它们）：
 *  - `byVariant` 按 `(model_id, variant)` 取一行 ⇒ 对应那张表的唯一索引；
 *  - `finishVerify` 自增 `verify_attempts` ⇒ 对应 SQL 里的 `verify_attempts + 1`；
 *  - `updateProgress` 只动 `bytes_downloaded` 与 `state` ⇒ 对应那条「续传的生命线」UPDATE。
 */
internal class FakeModelAssetDao : ModelAssetDao {

    val rows = LinkedHashMap<String, ModelAssetEntity>()

    /** 每个 id 被 `updateProgress` 的次数：用来断言「进度写库的频率」而不是只看最终值。 */
    val progressWrites = HashMap<String, Int>()

    override suspend fun upsert(asset: ModelAssetEntity) {
        rows[asset.id] = asset
    }

    override suspend fun byVariant(modelId: String, variant: String): ModelAssetEntity? =
        rows.values.firstOrNull { it.modelId == modelId && it.variant == variant }

    override suspend fun byId(id: String): ModelAssetEntity? = rows[id]

    override suspend fun allOnce(): List<ModelAssetEntity> = rows.values.toList()

    override suspend fun currentReady(): ModelAssetEntity? =
        rows.values.filter { it.state == "READY" }.maxByOrNull { it.lastUsedAt ?: 0L }

    override fun observeReady(): Flow<List<ModelAssetEntity>> = flowOf(rows.values.filter { it.state == "READY" })

    override fun all(): Flow<List<ModelAssetEntity>> = flowOf(rows.values.toList())

    override suspend fun updateProgress(id: String, bytes: Long, state: String) {
        val row = rows[id] ?: return
        rows[id] = row.copy(bytesDownloaded = bytes, state = state)
        progressWrites[id] = (progressWrites[id] ?: 0) + 1
    }

    override suspend fun finishVerify(
        id: String,
        state: String,
        localPath: String?,
        tempFile: String?,
        at: Long,
        actualBytes: Long?,
        actualSha: String?,
        result: String?,
    ) {
        val row = rows[id] ?: return
        rows[id] = row.copy(
            state = state,
            localPath = localPath,
            tempFile = tempFile,
            verifiedAt = at,
            actualBytes = actualBytes,
            actualSha256 = actualSha,
            verifyAttempts = row.verifyAttempts + 1,
            verifyLastResult = result,
        )
    }

    override suspend fun setError(id: String, error: String?, at: Long) {
        val row = rows[id] ?: return
        rows[id] = row.copy(lastError = error, lastErrorAt = at)
    }

    override suspend fun markLoaded(id: String, at: Long, backend: String?, loadMs: Long?): Unit =
        unsupported("markLoaded")

    override suspend fun delete(id: String): Unit = unsupported("delete")

    override suspend fun pendingTempFiles(): List<String> =
        rows.values.mapNotNull { it.tempFile }.filter { name ->
            rows.values.any { it.tempFile == name && it.state != "READY" }
        }

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("FakeModelAssetDao.$name 未被实现：本轮测试不该走到这里")
}

package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.CloudCredentialEntity
import kotlinx.coroutines.flow.Flow

/**
 * 云端凭证 DAO（docs/09 §2.1）。
 *
 * ## ★ 为什么这张表**零二级索引**
 *
 * 行数上界是「人手录入凭证的个数」（现实个位数），全表扫描比维护索引便宜。
 * 且墓碑行占位会让 `UNIQUE(label)` 之类的约束变成「删掉的别名不能再用」这种用户可见的 bug
 * ⇒ 查重由 store 层在事务内 `SELECT` 完成，不交给唯一索引（docs/09 §2.1 索引取舍）。
 *
 * ⚠ **失效条件（写死）**：若将来出现批量导入凭证（行数 > ~100），上面这个结论要重估。
 *
 * ## ★ 删除 = 密码学粉碎，不是 DELETE
 *
 * `shred()` 把 `secret_enc` 置 NULL + 写 `deleted_at`（墓碑），行保留
 * ⇒ 已有审计行（`route_decision.credential_id`）仍能解析出「当时用的是哪个凭证」。
 */
@Dao
interface CloudCredentialDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: CloudCredentialEntity)

    /** ★ 覆盖式写入（upsert 语义）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CloudCredentialEntity)

    /**
     * ★ 密码学粉碎：清空密文 + 写墓碑。**行保留**（审计可回溯）。
     * @return 受影响行数；0 = 凭证不存在或已被粉碎过（幂等）。
     */
    /**
     * ⚠ 三个参数**全显式**，不用 Kotlin 默认参数：Room 对 DAO 方法的默认实参支持
     *   会随版本漂移，而这条 SQL 的语义必须一眼可读（粉碎 = 密文置空 + 写墓碑）。
     */
    @Query(
        """
        UPDATE cloud_credential
           SET secret_enc = NULL, base_url_enc = :shredded, deleted_at = :now, updated_at = :now
         WHERE cred_id = :credId AND deleted_at IS NULL
        """,
    )
    suspend fun shred(credId: String, now: Long, shredded: ByteArray): Int

    /** ★ 彻底删除（只给「一键清除」或用户要求彻底清除时用）。 */
    @Query("DELETE FROM cloud_credential WHERE cred_id = :credId")
    suspend fun deleteHard(credId: String): Int

    /** ★ 一键清除：全表物理删除（配合 ① 删 Keystore 别名 ⇒ 密码学粉碎已先行完成）。 */
    @Query("DELETE FROM cloud_credential")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM cloud_credential WHERE cred_id = :credId")
    suspend fun byId(credId: String): CloudCredentialEntity?

    /** ★ UI 列表（`refs()`）：只列存活的。Room 的 InvalidationTracker 会自动让 Flow 重发。 */
    @Query("SELECT cred_id FROM cloud_credential WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun liveIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM cloud_credential WHERE cred_id = :credId AND deleted_at IS NULL")
    suspend fun alive(credId: String): Int

    /** ★ 「有哪些会话正在用这个凭证」（docs/08 §6.7：删凭证前的提示）。 */
    @Query("SELECT COUNT(*) FROM chat_session WHERE cloud_credential_id = :credId")
    suspend fun sessionUseCount(credId: String): Int

    /** 最近一次出云时间回填。 */
    @Query("UPDATE cloud_credential SET last_used_at = :now WHERE cred_id = :credId")
    suspend fun touchUsed(credId: String, now: Long): Int

    /**
     * ★★ D2「落盘字节 Canary」的读取口：绕过 `FieldCipher.open()` **原样读回密文字节**。
     *
     * 存在意义：如果只「seal 再 open 看值对不对」，一个直接返回明文的 no-op 实现
     * **也能通过**（它原样返回明文，值当然对）。必须验的是「磁盘上的字节不是明文」。
     */
    @Query("SELECT base_url_enc FROM cloud_credential WHERE cred_id = :credId")
    suspend fun rawBaseUrlBlob(credId: String): ByteArray?

    @Query("SELECT secret_enc FROM cloud_credential WHERE cred_id = :credId")
    suspend fun rawSecretBlob(credId: String): ByteArray?

    /** 自检 / 测试用：非规范字面量统计（docs/09 §5.3 的验证式收尾）。 */
    @Query("SELECT COUNT(*) FROM cloud_credential WHERE deleted_at IS NULL AND endpoint_host = ''")
    suspend fun countEmptyHost(): Int
}

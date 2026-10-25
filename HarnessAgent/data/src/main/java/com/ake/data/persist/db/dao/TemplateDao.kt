package com.ake.data.persist.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ake.data.persist.db.entity.TemplateRegistryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 模板注册表 DAO（§3.5 / §6.7）。
 *
 * ★ 内置模板的填充**不在迁移里**（上游 §7.3 已定，§6.7 确认）：
 * 模板是**内容**不是 schema，放迁移里会让「模板改一个字」变成一次 schema 变更。
 * 由 `bootstrap/TemplateBootstrap.ensureBuiltins()` 按 `(template_id, revision)` 幂等写入。
 */
@Dao
interface TemplateDao {

    @Query("SELECT * FROM template_registry ORDER BY source ASC, name ASC")
    fun observeAll(): Flow<List<TemplateRegistryEntity>>

    @Query("SELECT * FROM template_registry WHERE source = 'BUILTIN' ORDER BY name ASC")
    suspend fun builtins(): List<TemplateRegistryEntity>

    @Query("SELECT * FROM template_registry WHERE template_id = :templateId")
    suspend fun byId(templateId: String): TemplateRegistryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: TemplateRegistryEntity)

    /** 升级：revision 更高时整行覆盖（§6.7：`installed_at` 要保留 ⇒ 由调用方构造行）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TemplateRegistryEntity)

    @Query("DELETE FROM template_registry WHERE template_id = :templateId")
    suspend fun delete(templateId: String): Int

    @Query("DELETE FROM template_registry WHERE source = 'INSTALLED'")
    suspend fun deleteInstalled(): Int

    @Query("SELECT COUNT(*) FROM template_registry")
    suspend fun count(): Int
}

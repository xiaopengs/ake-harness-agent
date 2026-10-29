package com.ake.data.persist.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ★★ 云端凭证（元数据 + 密文）。**这张表没有任何一列能装明文秘密**（docs/09 §2.1）。
 *
 * ## A5 裁决：密文进 Room，不进独立文件
 *
 * 顾图南 `docs/08` §6.6 推荐落 `filesDir/credentials/v1/<ref>.bin`，被推翻，两条论据
 * 经代码核实均不成立：
 *
 *  ① 「Room 有备份 / 导出路径，独立文件没有」⇒ **`app/src/main/AndroidManifest.xml`
 *     已是 `allowBackup="false"` + `fullBackupContent="false"`，且 `data_extraction_rules.xml`
 *     对 cloud-backup 与 device-transfer **双双排除 file 与 database 两个 domain** ⇒ 两种落点等价。**
 *  ② 「exportSchema JSON 会泄漏」⇒ **Room 导出的 schema JSON 只有 entities/fields/indices/
 *     createSql/identityHash，装不进任何一行数据**（已逐字段核对 `data/schemas/.../3.json`）。
 *
 * 反过来，独立文件有一个 Room 落点没有的真缺口：**清除清单上多一条必须被人类记住的路径**。
 * `WipeEverythingUseCase` ① 删 Keystore 别名 + ③ 删库文件 ⇒ 本表**零新增清除步骤**就被覆盖；
 * 独立文件漏掉那一步 ⇒ 「一键清除」变成假的。见 docs/09 §4.2 问 3。
 *
 * ## 三条字段纪律
 *
 *  ① **`base_url_enc` 也必须加密。** 自建网关常把 key 放在 query 里（`?key=xxx`）——
 *     `docs/08` §6.3 ③ 亲自点名。只加密 key 而把 URL 明文留在库里，等于那份清单白写三分之一。
 *  ② **`endpoint_host` 是唯一的明文派生值**，且必须由 `:core:common/Hosts.normalise` 产出
 *     （**全仓唯一归一化实现**，禁止再抄一份 —— 曾因此出过真实的黑名单绕过安全 bug）。
 *  ③ **`secret_enc = NULL` 是一个状态，不是缺陷**：它表示密钥材料已被密码学粉碎。
 *     删凭证**不 DELETE 行**，而是置 NULL + 写 `deleted_at`（墓碑）⇒ 历史审计行仍可解析。
 *
 * ## ⚠ 为什么不能用 `PersistenceRedactionGate.sealField()`
 *
 * 那个方法会先 `scrub(text)` 再加密 —— 对 API Key / BaseURL 会**改坏内容**。
 * 本表的两个密文列一律走裸 `FieldCipher.seal(plain, aad)`（不做脱敏）。
 * AAD 绑定 `credId` 的字节 ⇒ 密文被跨行搬移时 GCM tag 校验必然失败。
 */
@Entity(tableName = "cloud_credential")
data class CloudCredentialEntity(
    /** CredentialRef.id —— ULID。**不是秘密**，可安全进日志 / Intent。 */
    @PrimaryKey @ColumnInfo(name = "cred_id") val credId: String,

    /** 用户起的别名（UI 显示）。 */
    @ColumnInfo(name = "label") val label: String,

    /** ★ `Hosts.normalise(baseUrl)` 的结果。**非秘密**（UI 本就要显示它）。 */
    @ColumnInfo(name = "endpoint_host") val endpointHost: String,

    /** ★★ 完整 BaseURL 的密文。aad = credId.toByteArray()。 */
    @ColumnInfo(name = "base_url_enc") val baseUrlEnc: ByteArray,

    /** ★★ API Key 的密文。**NULL = 已密码学粉碎 / 从未写入**。 */
    @ColumnInfo(name = "secret_enc") val secretEnc: ByteArray?,

    @ColumnInfo(name = "model_name") val modelName: String,

    /** REQUIRED | ALLOW_INSECURE_LAN（docs/08 §6.5）。 */
    @ColumnInfo(name = "tls_mode", defaultValue = "'REQUIRED'") val tlsMode: String,

    /** 用户声明的 context window；NULL ⇒ 用 `EndpointConfig.DEFAULT_CONTEXT_WINDOW`。 */
    @ColumnInfo(name = "declared_context_window") val declaredContextWindow: Int?,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 最近一次真正用它出云的时间。NULL ⇒ 从未用过。 */
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long?,

    /** ★ 密码学粉碎时间戳。NULL = 存活；非 NULL = 墓碑（行留、密文已清空）。 */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,

    @ColumnInfo(name = "schema_v", defaultValue = "1") val schemaV: Int,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1

        /** 墓碑行的密文占位。**必须不是合法 GCM 信封**（SecretEnvelope.MIN_LEN = 29）。 */
        val SHREDDED: ByteArray = ByteArray(0)

        /** 归一化后为空 host ⇒ 拒收（fail-closed，不猜一个端点）。 */
        fun isUsableHost(host: String): Boolean = host.isNotBlank()
    }
}

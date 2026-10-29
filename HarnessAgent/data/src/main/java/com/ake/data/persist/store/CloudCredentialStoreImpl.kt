package com.ake.data.persist.store

import com.ake.core.common.AkeLog
import com.ake.core.common.AppError
import com.ake.core.common.Clock
import com.ake.core.common.FieldCipher
import com.ake.core.common.Hosts
import com.ake.core.common.Outcome
import com.ake.core.ports.CloudCredentialPort
import com.ake.core.ports.CloudFailure
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.EndpointConfig
import com.ake.core.ports.PlainSecret
import com.ake.data.persist.db.dao.CloudCredentialDao
import com.ake.data.persist.db.entity.CloudCredentialEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ★★ `CloudCredentialPort` 的 Room 实现（A5 裁决的落点，docs/09 §4）。
 *
 * ## 为什么密文在 Room 而不是独立文件 `filesDir/credentials/<ref>.bin`
 *
 * ⚠ 编辑本文件头部注释时注意：**Kotlin 的块注释是可嵌套的**。上面这行原本写成
 *   「credentials 目录下星号点 bin」的写法，其中的「斜杠紧跟星号」会开启一个
 *   **嵌套注释** ⇒ 外层注释永远不闭合 ⇒ 整个类"消失" ⇒ KSP 报
 *   `CloudCredentialStoreImpl could not be resolved`。
 *   报的是"类型找不到"，真正的错误却是注释没闭合 —— **症状与病因相距很远**。
 *   （我自己就在这段注释里第二次踩了同一个坑；改这段注释时别把那个字符序列写回来。）
 *
 * 顾图南 `docs/08` §6.6 推荐独立文件，两条论据经代码核实**均不成立**：
 *  ① 「Room 有备份路径」⇒ `allowBackup="false"` + `fullBackupContent="false"`
 *     + `data_extraction_rules.xml` 对 cloud-backup / device-transfer **双双排除
 *     file 与 database 两个 domain** ⇒ 两种落点在这条路上完全等价。
 *  ② 「exportSchema JSON 会泄漏」⇒ schema JSON 只有 entities/fields/indices/
 *     createSql/identityHash，**装不进任何一行数据**（已核对 `data/schemas/.../4.json`）。
 *
 * 反过来，独立文件有个 Room 落点没有的真缺口：**清除清单多一条必须被人类记住的路径**。
 * `WipeEverythingUseCase` ① 删 Keystore 别名 + ③ 删库 ⇒ 本表**零新增清除步骤**就被覆盖。
 *
 * ## ★ 两条字段纪律
 *
 *  ① **完整 BaseURL 也加密。** 自建网关常把 key 放 query 里（`?key=xxx`，docs/08 §6.3 ③）
 *     ⇒ 完整 URL 本身就是秘密载体。只加密 key 而明文留 URL，等于那份清单白写三分之一。
 *  ② **只留 `Hosts.normalise(baseUrl)` 的结果作明文。** 且**必须走 `:core:common/Hosts`
 *     这一份唯一实现** —— 归一化在本工程是域名策略的判定前提，曾因多份不等价实现
 *     出过真实的黑名单绕过安全 bug。这里再抄一份就是重蹈覆辙。
 *
 * ## ⚠ 为什么不用 `PersistenceRedactionGate.sealField()`
 *
 * 那个方法会先 `scrub(text)` 再加密 —— 对 API Key / BaseURL 会**改坏内容**。
 * 本表两个密文列一律走裸 `FieldCipher.seal(plain, aad)`。
 * AAD 绑 `credId` 的字节 ⇒ 密文被跨行搬移时 GCM tag 校验必然失败。
 *
 * ## Keystore 别名
 *
 * MVP **复用既有 `ake.trace.dek.v1`**（docs/09 §4.5）⇒ `:app` 零改动，
 * 且 `WipeEverythingUseCase` ① 已删这个别名、③ 已删库 ⇒ 清除链路自动覆盖。
 * 翻案条件见 docs/09 §4.5（出现「退出云端」入口或需要独立密钥轮换时拆专用别名）。
 */
public class CloudCredentialStoreImpl internal constructor(
    private val dao: CloudCredentialDao,
    private val cipher: FieldCipher,
    private val clock: Clock,
) : CloudCredentialPort {

    override suspend fun upsert(
        ref: CredentialRef,
        cfg: EndpointConfig,
        secret: PlainSecret,
    ): Outcome<Unit> {
        // ★ host 归一化走全仓唯一实现；空 host ⇒ 拒收（fail-closed，不猜一个端点）。
        val host = Hosts.normalise(cfg.baseUrl)
        if (!CloudCredentialEntity.isUsableHost(host)) {
            AkeLog.w(TAG, "upsert rejected: unparseable baseUrl host for ref=${ref.id}")
            return Outcome.Err(
                AppError(
                    code = CODE_BAD_ENDPOINT,
                    reasonKey = "err_credential_bad_endpoint",
                    severity = com.ake.core.common.Severity.MEDIUM,
                ),
            )
        }
        val now = clock.nowMillis()
        val aad = ref.id.toByteArray()
        val existing = runCatching { dao.byId(ref.id) }.getOrNull()
        val row = CloudCredentialEntity(
            credId = ref.id,
            label = cfg.label,
            endpointHost = host,
            baseUrlEnc = cipher.seal(cfg.baseUrl.toByteArray(), aad),
            secretEnc = secret.useAsBearer { cipher.seal(it.toByteArray(), aad) },
            modelName = cfg.modelName,
            tlsMode = cfg.tlsMode.name,
            declaredContextWindow = cfg.declaredContextWindow,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastUsedAt = existing?.lastUsedAt,
            deletedAt = null,
            schemaV = CloudCredentialEntity.SCHEMA_VERSION,
        )
        return runCatching { dao.upsert(row) }
            .fold(
                onSuccess = { Outcome.Ok(Unit) },
                onFailure = { t ->
                    AkeLog.w(TAG, "upsert failed ref=${ref.id}: ${t.message}")
                    Outcome.Err(AppError(AppError.CODE_IO, "err_credential_write", t))
                },
            )
    }

    override fun refs(): Flow<List<CredentialRef>> =
        dao.liveIds().map { ids -> ids.map { CredentialRef(it) } }

    /**
     * ★ 读配置。`EndpointConfig.baseUrl` 需要完整 URL（UI 编辑用）⇒ 这里解密回填。
     *
     * ⚠ 调用方**展示时必须用 `cfg.hostOf()`**，不得把 `baseUrl` 原文打到 UI / 日志
     *    （docs/08 §6.3 ③）—— `EndpointConfig` 之所以提供 `hostOf()` 就是这个原因。
     */
    override suspend fun configOf(ref: CredentialRef): Outcome<EndpointConfig> {
        val row = runCatching { dao.byId(ref.id) }.getOrNull()
            ?: return Outcome.Err(credentialMissing())
        if (row.deletedAt != null) return Outcome.Err(credentialMissing())
        val aad = ref.id.toByteArray()
        val url = cipher.open(row.baseUrlEnc, aad)?.let { String(it) }
            ?: return Outcome.Err(credentialUnreadable())
        return Outcome.Ok(
            EndpointConfig(
                baseUrl = url,
                modelName = row.modelName,
                tlsMode = runCatching { com.ake.core.ports.TlsMode.valueOf(row.tlsMode) }
                    .getOrDefault(com.ake.core.ports.TlsMode.REQUIRED),
                declaredContextWindow = row.declaredContextWindow,
                label = row.label,
            ),
        )
    }

    /**
     * ★★ 唯一能拿到明文的地方。
     *
     * ★ 密钥已被销毁（`open` 返回 null）时返回 **CREDENTIAL_MISSING 之外的错误**
     *   —— 与「行不存在」区分开，否则用户看到 `CREDENTIAL_MISSING` 却不知道为什么。
     */
    override suspend fun secretOf(ref: CredentialRef): Outcome<PlainSecret> {
        val row = runCatching { dao.byId(ref.id) }.getOrNull()
            ?: return Outcome.Err(credentialMissing())
        if (row.deletedAt != null || row.secretEnc == null) return Outcome.Err(credentialMissing())
        val aad = ref.id.toByteArray()
        val plain = cipher.open(row.secretEnc!!, aad)?.let { String(it) }
            ?: return Outcome.Err(credentialUnreadable())
        return Outcome.Ok(PlainSecret.of(plain))
    }

    /**
     * ★ 删除 = **密码学粉碎**（置 NULL + 墓碑），**不是 DELETE 行**。
     *
     * 行保留 ⇒ 已有审计行（`route_decision.credential_id`）仍能解析出
     * 「当时用的是哪个凭证」（docs/09 §2.1 ③）。
     */
    override suspend fun delete(ref: CredentialRef): Outcome<Unit> {
        val rows = runCatching {
            dao.shred(ref.id, clock.nowMillis(), CloudCredentialEntity.SHREDDED)
        }.getOrNull()
        return if (rows == null) {
            Outcome.Err(AppError(AppError.CODE_IO, "err_credential_write"))
        } else {
            Outcome.Ok(Unit)
        }
    }

    override suspend fun exists(ref: CredentialRef): Boolean =
        runCatching { dao.alive(ref.id) }.getOrDefault(0) > 0

    /** ★ 彻底物理删除（只给「一键清除」用，正常删除走 [delete] 的墓碑）。 */
    public suspend fun purgeAll(): Int = runCatching { dao.deleteAll() }.getOrDefault(0)

    /** ★ 「有哪些会话正在用这个凭证」（docs/08 §6.7 删凭证前的提示）。 */
    public suspend fun sessionUseCount(ref: CredentialRef): Int =
        runCatching { dao.sessionUseCount(ref.id) }.getOrDefault(0)

    private fun credentialMissing(): AppError = AppError(
        code = com.ake.core.ports.CloudFailureCodes.CREDENTIAL_MISSING,
        reasonKey = CloudFailure.CredentialMissing.reasonKey,
        severity = com.ake.core.common.Severity.MEDIUM,
    )

    private fun credentialUnreadable(): AppError = AppError(
        code = CODE_UNREADABLE,
        reasonKey = "err_credential_unreadable",
        severity = com.ake.core.common.Severity.MEDIUM,
    )

    public companion object {
        public const val TAG: String = "CloudCredentialStore"
        public const val CODE_BAD_ENDPOINT: String = "CREDENTIAL_BAD_ENDPOINT"
        public const val CODE_UNREADABLE: String = "CREDENTIAL_UNREADABLE"
    }
}

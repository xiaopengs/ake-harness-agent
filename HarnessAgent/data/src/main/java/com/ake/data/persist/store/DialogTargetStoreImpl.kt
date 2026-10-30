package com.ake.data.persist.store

import com.ake.core.common.Clock
import com.ake.core.common.Outcome
import com.ake.core.ports.CredentialRef
import com.ake.core.ports.DialogTarget
import com.ake.core.ports.DialogTargetCodec
import com.ake.core.ports.DialogTargetStore
import com.ake.data.persist.db.dao.ChatSessionDao

/**
 * ★★ `DialogTargetStore` 的 Room 实现（docs/10 §6.2-D 的落库面）。
 *
 * ## ★ 落点：`chat_session.model_target` 列（v4，`CloudMigration3To4` 已建）
 *
 * ⚠ **事实校正**：docs/10 与主理人的转述里提到「v4 的 `route_policy` 表」。
 *   实际 `CloudMigration3To4` **没有** `route_policy` 表 ——
 *   会话级目标落在 `chat_session` 上加的三列：
 *   `model_target` / `cloud_credential_id` / `cloud_consent_at`。
 *   本实现按**实际 schema** 写，没有新建表、没有改迁移 SQL。
 *
 * ## ★ 为什么必须换成 Room：内存实现重启即丢
 *
 * `InMemoryDialogTargetStore` 会让「用户在设置页选了云端模型」这件事
 * 在**下次冷启动后静默变回端侧**。这不是崩溃，是**用户以为自己在用某个模型、
 * 实际在用另一个** —— 与本轮红线（静默降级）同一类错误，只是没有出境维度。
 *
 * ## ★ 编解码仍然只有一份
 *
 * 文本形态全在 [DialogTargetCodec]（`:core:ports`）。本类只负责「存哪、怎么写」，
 * ⇒ 不会出现第二份不等价的解析。
 */
public class DialogTargetStoreImpl internal constructor(
    private val sessions: ChatSessionDao,
    private val clock: Clock,
) : DialogTargetStore {

    override suspend fun of(sessionId: String): DialogTarget? {
        // ★ NULL 与 "LOCAL" 必须区分：前者是「没登记过」，后者是「登记了端侧」。
        val raw = sessions.modelTargetOf(sessionId) ?: return null
        return DialogTargetCodec.decode(raw)
    }

    override suspend fun select(sessionId: String, target: DialogTarget): Outcome<Unit> {
        val credId = (target as? DialogTarget.Remote)?.credentialRef?.id
        val rows = sessions.bindCredential(
            sessionId = sessionId,
            credId = credId,
            modelTarget = DialogTargetCodec.encode(target),
            now = clock.nowMillis(),
        )
        // ★ 0 行 ⇒ 会话不存在。返回 Err 而不是假装成功 —— 否则「选了目标」这句话是假的。
        return if (rows > 0) {
            Outcome.Ok(Unit)
        } else {
            Outcome.Err(
                com.ake.core.common.AppError(
                    code = CODE_SESSION_NOT_FOUND,
                    reasonKey = "err_target_session_not_found",
                    severity = com.ake.core.common.Severity.MEDIUM,
                ),
            )
        }
    }

    public companion object {
        public const val CODE_SESSION_NOT_FOUND: String = "TARGET_SESSION_NOT_FOUND"

        /** 测试 / 组合根便捷构造。 */
        public fun of(sessions: ChatSessionDao, clock: Clock): DialogTargetStoreImpl =
            DialogTargetStoreImpl(sessions, clock)
    }
}

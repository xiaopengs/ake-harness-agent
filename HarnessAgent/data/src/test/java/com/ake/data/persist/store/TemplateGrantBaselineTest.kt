package com.ake.data.persist.store

import com.ake.core.common.ManualClock
import com.ake.core.common.Outcome
import com.ake.core.ports.AgentProfileDraft
import com.ake.core.ports.CapabilityKind
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ProfileOverrides
import com.ake.data.persist.bootstrap.TemplateAssets
import com.ake.data.persist.bootstrap.TemplateBootstrap
import com.ake.data.persist.bootstrap.TemplateCapabilityBaseline
import com.ake.data.persist.fake.FakeAgentProfileDao
import com.ake.data.persist.fake.FakeCapabilityGrantDao
import com.ake.data.persist.fake.FakeFieldCipher
import com.ake.data.persist.fake.FakeTemplateDao
import com.ake.data.persist.fake.UnsupportedChatMessageDao
import com.ake.data.persist.fake.UnsupportedChatSessionDao
import com.ake.data.persist.fake.UnsupportedMemoryDao
import com.ake.data.persist.fake.agentProfileEntity
import com.ake.data.persist.fake.templateRegistryEntity
import com.ake.data.persist.redact.PersistenceRedactionGate
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * ★★ 模板 → Agent 的**默认授权**（本轮补的那个会让「聊天看着像坏了」的缺口）。
 *
 * ## 缺口是什么
 *
 * `agent_capability_grant` 为空 ⇒ `CapabilityGrantStoreImpl.grantsOf()` 返回空 map
 * ⇒ §5.4 的 `tool_registry.enabled AND grant[agentId][ref]` 恒为 false。
 * 于是人格连 `builtin.browser_task` 都不可用：聊天只回文字、永不派生任务。
 * **用户看到的是「功能没接上」，而不是「你没勾权限」** —— 数据层表现为「一张合法的空表」。
 *
 * ## 三条要守住的性质（对应设计的三个层次）
 *
 *  ① **新建**：从模板实例化的人格，默认就能用它模板的核心技能 ——
 *    即使调用方（创建页）传下来的是**空清单**。空清单正是那只哑 Agent 的来源；
 *  ② **不越权**：模板没声明过的能力仍然是 false；市场模板一条都不预置；
 *    而且这里**只写 `agent_capability_grant`**，不碰 `tool_registry.enabled`（两级授权取交集）。
 *  ③ **存量**：本改动之前建好的人格（授权表为空）在 bootstrap 时被补一次，
 *    补过之后不再重复写、也不会推翻用户后来的逐条撤销。
 *
 * ## 为什么用内存 DAO 而不是真 Room
 *
 * 与 `McpServerStoreImplTest` 同一条纪律（`:data` 是 androidLibrary，JVM 单测起不了真库）：
 * 把 `agent_capability_grant` 的复合主键语义在内存里重实现一遍，
 * 验证的是**调用方与守卫之间的契约**，不是 SQL 字符串本身。
 */
@RunWith(JUnit4::class)
class TemplateGrantBaselineTest {

    private val templates = FakeTemplateDao()
    private val profiles = FakeAgentProfileDao()
    private val grants = FakeCapabilityGrantDao()
    private val clock = ManualClock(1_000L)
    private val gate = PersistenceRedactionGate(FakeFieldCipher())

    /** 读取侧就用真实现 —— 「能不能用」的判据必须与 App 里同一个。 */
    private val grantStore = CapabilityGrantStoreImpl(grants, clock)

    private val browserTask = CapabilityRef.builtin(TemplateAssets.BROWSER_TASK)

    private fun profileStore(): AgentProfileStoreImpl = AgentProfileStoreImpl(
        profiles = profiles,
        grants = grants,
        sessions = UnsupportedChatSessionDao(),
        messages = UnsupportedChatMessageDao(),
        memory = UnsupportedMemoryDao(),
        gate = gate,
        clock = clock,
        // 事务在本测试里退化为直接执行：真事务语义不是本类的断言对象
        // （顺序语义仍成立 —— 同一个协程里串行跑）。
        transaction = { block -> block() },
    )

    private fun templateStore(): TemplateStoreImpl = TemplateStoreImpl(
        dao = templates,
        gate = gate,
        profiles = profileStore(),
        grants = grants,
        clock = clock,
    )

    private fun installBuiltin(templateId: String) {
        templates.put(templateRegistryEntity(templateId, source = "BUILTIN"))
    }

    private fun instantiate(
        templateId: String,
        capabilities: List<CapabilityRef>?,
    ): String = runBlocking {
        val outcome = templateStore().instantiate(
            templateId = templateId,
            overrides = ProfileOverrides(name = "测试人格", capabilities = capabilities),
        )
        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        (outcome as Outcome.Ok).value
    }

    // ──────────────────────────────────────────── ① 新建路径

    @Test
    fun `从模板创建的人格默认能用它的核心技能`(): Unit = runBlocking {
        installBuiltin(TemplateAssets.VIDEO_DIRECTOR.templateId)

        // ★ 关键：创建页在用户什么都没勾时传下来的就是空清单
        val id = instantiate(TemplateAssets.VIDEO_DIRECTOR.templateId, capabilities = emptyList())

        assertThat(grants.grantedRefs(id)).containsExactly("BUILTIN:${TemplateAssets.BROWSER_TASK}")
        // ★ 用 App 里同一个判据读一遍：ChatSkillRegistry 看到的那个 map
        val map = grantStore.grantsOf(id).first()
        assertThat(map[browserTask]).isTrue()
    }

    @Test
    fun `未声明的能力仍然是 false`(): Unit = runBlocking {
        installBuiltin(TemplateAssets.CODE_EXPERT.templateId)
        val id = instantiate(TemplateAssets.CODE_EXPERT.templateId, capabilities = null)

        val map = grantStore.grantsOf(id).first()
        // 模板没声明过的一律没有：内置的另一项、任何 MCP 工具、任何插件
        assertThat(map[CapabilityRef.builtin("builtin.file_write")]).isNull()
        assertThat(map[CapabilityRef.tool("mcp.local.fs.write")]).isNull()
        assertThat(map[CapabilityRef.plugin("plugin.any")]).isNull()
        // 而且**只**多了基线这一条，没有顺手给全量
        assertThat(map.keys).containsExactly(browserTask)
    }

    @Test
    fun `用户额外勾选的能力会被保留，基线在前`(): Unit = runBlocking {
        installBuiltin(TemplateAssets.LEGAL_COUNSEL.templateId)
        val extra = CapabilityRef.tool("mcp.local.read")
        val id = instantiate(TemplateAssets.LEGAL_COUNSEL.templateId, capabilities = listOf(extra))

        assertThat(grants.grantedRefs(id))
            .containsExactly("BUILTIN:${TemplateAssets.BROWSER_TASK}", "MCP_TOOL:mcp.local.read")
            .inOrder()
        assertThat(grantStore.grantsOf(id).first()[extra]).isTrue()
    }

    @Test
    fun `市场模板一条都不预置`(): Unit = runBlocking {
        // 市场模板：source = INSTALLED，没有 TemplateAssets 元数据 ⇒ 也没有基线
        templates.put(templateRegistryEntity("market.remote", source = "INSTALLED"))

        val id = instantiate("market.remote", capabilities = emptyList())

        // §6.5 ③：授权永不由 bundle 决定 —— 用户没勾就一条都没有
        assertThat(grants.grantedRefs(id)).isEmpty()
    }

    // ──────────────────────────────────────────── ② 不越权（声明层）

    @Test
    fun `基线只含模板声明过的能力且只含 BUILTIN`() {
        for (template in TemplateAssets.builtins()) {
            val baseline = TemplateCapabilityBaseline.requiredFor(template.templateId)
            // 空基线 = 装出来还是哑的，那正是本次要消灭的状态
            assertThat(baseline).isNotEmpty()
            assertThat(baseline.size).isAtMost(TemplateCapabilityBaseline.MAX_PER_TEMPLATE)
            for (ref in baseline) {
                assertThat(ref.kind).isEqualTo(CapabilityKind.BUILTIN)
                // 只预置模板自己声明过的
                assertThat(template.suggestedCapabilities).contains(ref)
            }
        }
        // 非内置模板拿不到基线（§6.5 ③ 的边界）
        assertThat(TemplateCapabilityBaseline.requiredFor("market.remote")).isEmpty()
        assertThat(TemplateCapabilityBaseline.requiredFor(null)).isEmpty()
    }

    @Test
    fun `合并基线不会重复也不会丢用户的勾选`() {
        val tid = TemplateAssets.INVESTMENT_ADVISOR.templateId
        // 用户恰好也勾了核心技能 ⇒ 只能有一行（复合主键 + REPLACE 会重置 granted_at）
        assertThat(TemplateCapabilityBaseline.merge(tid, listOf(browserTask))).containsExactly(browserTask)
        // 市场模板：原样返回
        val extra = CapabilityRef.tool("mcp.local.read")
        assertThat(TemplateCapabilityBaseline.merge("market.remote", listOf(extra))).containsExactly(extra)
        // 没有模板（用户直接建人格）：不注入任何东西
        assertThat(TemplateCapabilityBaseline.merge(null, emptyList())).isEmpty()
    }

    @Test
    fun `用户直接建人格不会被注入基线`(): Unit = runBlocking {
        val outcome = profileStore().upsert(
            AgentProfileDraft(
                name = "手写人格",
                avatarUri = null,
                description = "",
                personaCharter = "你是一名助手。",
                workingDirUri = null,
                capabilityRequests = emptyList(),
                templateId = null,
            ),
        )
        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        assertThat(grants.grantedRefs((outcome as Outcome.Ok).value)).isEmpty()
    }

    // ──────────────────────────────────────────── ③ 存量回填

    @Test
    fun `存量哑人格在 bootstrap 时被补一次，重跑不写也不改 granted_at`(): Unit = runBlocking {
        val videoDirector = TemplateAssets.VIDEO_DIRECTOR.templateId
        // 本改动之前建好的人格：template_id 有，授权表一行都没有
        val dumbA = "01OLDPROFILE00000000000001"
        val dumbB = "01OLDPROFILE00000000000002"
        profiles.put(agentProfileEntity(dumbA, templateId = videoDirector))
        profiles.put(agentProfileEntity(dumbB, templateId = videoDirector))
        // 市场模板的人格：不该被补
        val market = "01OLDPROFILE00000000000003"
        profiles.put(agentProfileEntity(market, templateId = "market.remote"))

        val first = runBlocking { TemplateBootstrap.bootstrap(templates, profiles, grants, gate, clock) }
        assertThat(first.templatesWritten).isEqualTo(TemplateAssets.builtins().size)
        assertThat(first.grantsBackfilled).isEqualTo(2)
        assertThat(grants.grantedRefs(dumbA)).containsExactly("BUILTIN:${TemplateAssets.BROWSER_TASK}")
        assertThat(grants.grantedRefs(dumbB)).containsExactly("BUILTIN:${TemplateAssets.BROWSER_TASK}")
        assertThat(grants.grantedRefs(market)).isEmpty()

        // ── 重跑：模板不重写、授权不重写，且 granted_at 不被刷新（那是审计证据）
        val grantedAtBefore = grants.allRows.map { it.grantedAt }.distinct()
        clock.advance(60_000L)
        val second = runBlocking { TemplateBootstrap.bootstrap(templates, profiles, grants, gate, clock) }
        assertThat(second.templatesWritten).isEqualTo(0)
        assertThat(second.grantsBackfilled).isEqualTo(0)
        assertThat(grants.allRows.map { it.grantedAt }.distinct()).isEqualTo(grantedAtBefore)
    }

    @Test
    fun `已有人格不会被回填推翻用户的逐条撤销`(): Unit = runBlocking {
        val tid = TemplateAssets.SOCIAL_MEDIA_OPERATOR.templateId
        installBuiltin(tid)
        val id = instantiate(tid, capabilities = listOf(CapabilityRef.tool("mcp.local.read")))

        // 用户撤销核心能力、留下自选的那一项
        grantStore.setGranted(id, browserTask, granted = false)
        assertThat(grantStore.grantsOf(id).first()[browserTask]).isNull()

        // 下一次冷启的回填不该把它装回来（该人格的授权表不是空的）
        val inserted = runBlocking { TemplateBootstrap.ensureCapabilityGrants(profiles, grants, clock) }
        assertThat(inserted).isEqualTo(0)
        assertThat(grantStore.grantsOf(id).first()[browserTask]).isNull()
    }
}

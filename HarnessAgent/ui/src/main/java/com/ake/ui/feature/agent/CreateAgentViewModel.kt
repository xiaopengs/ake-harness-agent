package com.ake.ui.feature.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ake.core.common.Outcome
import com.ake.core.ports.AgentProfileDraft
import com.ake.core.ports.AgentProfileStorePort
import com.ake.core.ports.CapabilityRef
import com.ake.core.ports.ProfileOverrides
import com.ake.core.ports.SkillRegistryPort
import com.ake.core.ports.TemplateStorePort
import com.ake.ui.components.CapabilityOption
import com.ake.ui.components.toOption
import com.ake.ui.util.readableError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

public data class CreateAgentUi(
    val name: String = "",
    val description: String = "",
    val avatarUri: String = "",
    val persona: String = "",
    /** 从模板进来时带上，保存走 `TemplateStorePort.instantiate`，用来记录来源与 revision。 */
    val templateId: String? = null,
    val templateName: String? = null,
    /** 模板自带的合规声明：只展示，不可编辑（§Q3 裁决）。 */
    val disclaimer: String? = null,
    val options: List<CapabilityOption> = emptyList(),
    val selected: Set<CapabilityRef> = emptySet(),
    val limit: Int = CreateAgentViewModel.DEFAULT_MAX_SKILLS,
    /** ★ 技能注册表没接线时为 true：能力清单只来自模板建议，界面上要说清楚。 */
    val registryMissing: Boolean = false,
    val saving: Boolean = false,
    val created: Boolean = false,
    val errorText: String? = null,
    val nameMissing: Boolean = false,
    val personaMissing: Boolean = false,
)

/**
 * 创建 Agent 的 ViewModel。
 *
 * ## 两条入口，一个页面
 *
 * ① **从职业模板**：`templateId` 进参数，人格、描述、建议能力全部预填；
 * ② **空白创建**：全部为空，用户自己写。
 *
 * 两者共用同一个表单，因为它们的差别只有"初始值从哪来"。分两个页面会让
 * "模板里那段我不喜欢、想改两笔"变成一次页面跳转。
 *
 * ## 保存走哪条路径
 *
 * - 从模板进来 ⇒ `TemplateStorePort.instantiate(templateId, overrides)`：
 *   它内部会写 `templateId` / revision 并复用同一个 `upsert`，扫描也只跑一道门；
 * - 空白创建 ⇒ `AgentProfileStorePort.upsert(draft)`，`templateId = null` ⇒ 来源记为 USER_DIRECT。
 *
 * ## 能力上限 6 的判定放在这里，而不是只靠界面置灰
 *
 * 界面已经会把满额后的勾选框置灰（[com.ake.ui.components.CapabilityPicker]），
 * 但那只是**视觉**约束：一次长按、一次无障碍事件、或者将来加了"全选"，
 * 都可能绕过它。所以 `onToggle` 里再挡一次 —— 门只有一道的时候，
 * 它迟早会被绕过去。
 */
@HiltViewModel
public class CreateAgentViewModel @Inject constructor(
    private val profiles: AgentProfileStorePort,
    private val templates: TemplateStorePort,
    registry: Optional<SkillRegistryPort>,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val templateId: String? = savedStateHandle.get<String>(ARG_TEMPLATE_ID)
    private val prefillProfileId: String? = savedStateHandle.get<String>(ARG_PREFILL_PROFILE)

    private val registry: SkillRegistryPort? = registry.orElse(null)

    private val _ui = MutableStateFlow(CreateAgentUi(templateId = templateId))
    public val ui: StateFlow<CreateAgentUi> = _ui.asStateFlow()

    init {
        loadRegistrySkills()
        if (templateId != null) applyTemplate(templateId)
        if (prefillProfileId != null) applyPrefill(prefillProfileId)
    }

    // ──────────────────────────────────────────────── 表单

    public fun onNameChange(v: String): Unit = _ui.update { it.copy(name = v, nameMissing = false) }

    public fun onDescriptionChange(v: String): Unit = _ui.update { it.copy(description = v) }

    public fun onAvatarChange(v: String): Unit = _ui.update { it.copy(avatarUri = v) }

    public fun onPersonaChange(v: String): Unit = _ui.update { it.copy(persona = v, personaMissing = false) }

    public fun onToggleCapability(ref: CapabilityRef, granted: Boolean) {
        _ui.update { s ->
            val next = if (granted) {
                // 第二道闸：界面置灰之外再挡一次，且不做"静默丢最后一个"
                if (s.selected.size >= s.limit || ref in s.selected) s.selected else s.selected + ref
            } else {
                s.selected - ref
            }
            s.copy(selected = next)
        }
    }

    public fun save() {
        val s = _ui.value
        if (s.saving || s.created) return
        val nameMissing = s.name.isBlank()
        val personaMissing = s.persona.isBlank()
        if (nameMissing || personaMissing) {
            _ui.update { it.copy(nameMissing = nameMissing, personaMissing = personaMissing) }
            return
        }

        _ui.update { it.copy(saving = true, errorText = null) }
        viewModelScope.launch {
            val caps = _ui.value.selected.toList()
            val result: Outcome<String> = if (templateId != null) {
                templates.instantiate(
                    templateId = templateId,
                    overrides = ProfileOverrides(
                        name = _ui.value.name.trim(),
                        avatarUri = _ui.value.avatarUri.trim().ifBlank { null },
                        description = _ui.value.description.trim(),
                        personaCharter = _ui.value.persona,
                        capabilities = caps,
                    ),
                )
            } else {
                profiles.upsert(
                    AgentProfileDraft(
                        name = _ui.value.name.trim(),
                        avatarUri = _ui.value.avatarUri.trim().ifBlank { null },
                        description = _ui.value.description.trim(),
                        personaCharter = _ui.value.persona,
                        workingDirUri = null,
                        capabilityRequests = caps,
                        templateId = null,
                    ),
                )
            }

            _ui.update { cur ->
                when (result) {
                    is Outcome.Ok -> cur.copy(saving = false, created = true)
                    is Outcome.Err -> cur.copy(saving = false, errorText = readableError(result.error))
                }
            }
        }
    }

    // ──────────────────────────────────────────────── 预填

    private fun loadRegistrySkills() {
        val reg = registry ?: run {
            _ui.update { it.copy(registryMissing = true) }
            return
        }
        viewModelScope.launch {
            runCatching { reg.all().first() }
                .onSuccess { specs ->
                    val options = specs.map { it.toOption() }
                    _ui.update { s -> s.copy(options = mergeOptions(options, s.options), registryMissing = false) }
                }
                .onFailure { _ui.update { s -> s.copy(registryMissing = true) } }
        }
    }

    private fun applyTemplate(id: String) {
        viewModelScope.launch {
            when (val r = templates.byId(id)) {
                is Outcome.Ok -> {
                    val t = r.value
                    _ui.update { s ->
                        s.copy(
                            templateName = t.name,
                            name = s.name.ifBlank { t.name },
                            description = s.description.ifBlank { t.summary },
                            persona = s.persona.ifBlank { t.charter },
                            disclaimer = t.disclaimer,
                            // 模板建议的能力默认**勾上**（用户可以取消）：
                            // 它们不是"已授权"，只是"这个角色通常需要"
                            // 上限同样生效：模板建议超过 6 条时截到 6（并按 limit 截，不写死 6）
                            selected = (s.selected + t.suggestedCapabilities).take(s.limit).toSet(),
                            options = mergeOptions(s.options, t.suggestedCapabilities.map { it.toOption() }),
                        )
                    }
                }
                is Outcome.Err -> _ui.update { s -> s.copy(errorText = readableError(r.error)) }
            }
        }
    }

    private fun applyPrefill(profileId: String) {
        viewModelScope.launch {
            when (val r = profiles.byId(profileId)) {
                is Outcome.Ok -> {
                    val p = r.value
                    _ui.update { s ->
                        s.copy(
                            name = s.name.ifBlank { p.name },
                            description = s.description.ifBlank { p.description },
                            persona = s.persona.ifBlank { p.personaCharter },
                            avatarUri = s.avatarUri.ifBlank { p.avatarUri.orEmpty() },
                        )
                    }
                }
                is Outcome.Err -> _ui.update { s -> s.copy(errorText = readableError(r.error)) }
            }
        }
    }

    /**
     * 合并两份能力清单：注册表优先（有描述、有风险等级），模板建议里注册表没有的追加在后面。
     *
     * 用 `ref` 去重而不是 id：`CapabilityRef` 的相等性包含 `kind`，
     * 同一个 id 的 BUILTIN 与 MCP_TOOL 是两个不同的能力。
     */
    private fun mergeOptions(
        primary: List<CapabilityOption>,
        secondary: List<CapabilityOption>,
    ): List<CapabilityOption> {
        val seen = primary.mapTo(HashSet()) { it.ref }
        return primary + secondary.filterNot { it.ref in seen }
    }

    public companion object {
        /** `AgentProfileEntity.DEFAULT_MAX_SKILLS` 的 UI 侧镜像（文档 §5.4：默认 6、硬顶 12）。 */
        public const val DEFAULT_MAX_SKILLS: Int = 6

        public const val ARG_TEMPLATE_ID: String = "templateId"
        public const val ARG_PREFILL_PROFILE: String = "prefillProfileId"
    }
}

package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiTransport
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import java.util.UUID

/** 选择源 / 配置档 / 新建草稿的结果。 */
sealed interface EditOutcome {
    data object Applied : EditOutcome
    data object NeedsConfirmation : EditOutcome
    data class Failed(val reason: String) : EditOutcome
}

/** 删除当前配置档的计划；不可删除时 [reason] 必须可展示。 */
data class DeletePlan(
    val canDelete: Boolean,
    val reason: String?,
    val fallback: AiSettings?
)

/**
 * AI 源管理页的纯编辑状态（不依赖 Android，可直接 JVM 单测）。
 *
 * 职责边界：
 * - 本类只维护「正在编辑哪一份配置、是否脏、校验结果、删除回退」；
 * - 持久化仍由 `AiSettingsStore` 完成，页面在保存/删除后调用 [reload]；
 * - 有未保存内容时，选择操作返回 [EditOutcome.NeedsConfirmation]，由页面弹确认框；
 *   确认后带 `force = true` 再调用一次。
 */
class AiSettingsEditor(
    profiles: List<AiSettings>,
    active: AiSettings,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    var profiles: List<AiSettings> =
        if (profiles.any { it.id == active.id }) profiles.toList() else profiles + active
        private set

    var activeId: String = active.id
        private set

    var source: AiSourceId = active.source
        private set

    var editing: AiSettings = active
        private set

    /** 当前编辑的是尚未写入 store 的新配置。 */
    var isNewDraft: Boolean = false
        private set

    /** 用户已修改字段（不包含「新建但未改」的情况）。 */
    var dirty: Boolean = false
        private set

    var errors: AiFieldErrors = AiFieldErrors()
        private set

    /** 新建草稿也算未保存内容：直接返回会丢失它。 */
    val hasUnsavedChanges: Boolean get() = dirty || isNewDraft

    fun activeProfile(): AiSettings? = profiles.firstOrNull { it.id == activeId }

    fun activeSource(): AiSourceId? = activeProfile()?.source

    fun profilesForSource(): List<AiSettings> = profiles.filter { it.source == source }

    fun isEditingActive(): Boolean = !isNewDraft && editing.id == activeId

    fun selectSource(next: AiSourceId, force: Boolean = false): EditOutcome {
        if (next == source) return EditOutcome.Applied
        if (hasUnsavedChanges && !force) return EditOutcome.NeedsConfirmation
        loadSource(next)
        return EditOutcome.Applied
    }

    fun selectProfile(id: String, force: Boolean = false): EditOutcome {
        if (!isNewDraft && editing.id == id) return EditOutcome.Applied
        if (hasUnsavedChanges && !force) return EditOutcome.NeedsConfirmation
        val profile = profiles.firstOrNull { it.id == id }
            ?: return EditOutcome.Failed("配置档不存在")
        source = profile.source
        editing = profile
        isNewDraft = false
        dirty = false
        errors = AiFieldErrors()
        return EditOutcome.Applied
    }

    fun newProfile(force: Boolean = false): EditOutcome {
        if (hasUnsavedChanges && !force) return EditOutcome.NeedsConfirmation
        editing = presetFor(source)
        isNewDraft = true
        dirty = false
        errors = AiFieldErrors()
        return EditOutcome.Applied
    }

    fun update(field: AiField, value: String) {
        val next = when (field) {
            AiField.NAME -> editing.copy(provider = value)
            AiField.HOME_URL -> editing.copy(homeUrl = value)
            AiField.ENDPOINT -> editing.copy(endpoint = value)
            AiField.MODEL -> editing.copy(model = value)
            AiField.API_KEY -> editing.copy(apiKey = value)
        }
        if (next == editing) return
        editing = next
        dirty = true
        if (errors.message(field) != null) {
            errors = AiFieldErrors(errors.byField - field)
        }
    }

    /** 校验并保存结果，供页面渲染字段错误。 */
    fun validate(): AiFieldErrors {
        errors = AiSettingsValidation.validate(source, editing)
        return errors
    }

    /** 通过校验后用于写库的规范化配置。 */
    fun buildForSave(): AiSettings {
        val web = source.isWebView
        return editing.copy(
            sourceKey = source.key,
            provider = editing.provider.trim().ifBlank { source.label },
            homeUrl = if (web) editing.homeUrl.trim() else "",
            endpoint = if (web) "" else editing.endpoint.trim(),
            model = if (web) "" else editing.model.trim(),
            apiKey = if (web) "" else editing.apiKey.trim(),
            transport = if (web) AiTransport.WEBVIEW else AiTransport.API
        )
    }

    fun deletePlan(): DeletePlan {
        if (isNewDraft) {
            return DeletePlan(false, "新建配置尚未保存，不能删除", null)
        }
        if (profiles.size <= 1) {
            return DeletePlan(false, "至少保留一个配置档", null)
        }
        val fallback = profiles.firstOrNull { it.id != editing.id && it.source == source }
            ?: profiles.firstOrNull { it.id != editing.id }
        return DeletePlan(true, null, fallback)
    }

    /**
     * 配置变更重建后恢复草稿。
     *
     * API Key 不进 Bundle：已有配置从 [profiles] 里的原值恢复，新建配置由页面提示重新输入。
     */
    fun restoreDraft(draft: AiSettings, newDraft: Boolean, modified: Boolean) {
        source = draft.source
        editing = draft
        isNewDraft = newDraft
        dirty = modified
        errors = AiFieldErrors()
    }

    /** 删除/外部持久化后重新载入。 */
    fun reload(newProfiles: List<AiSettings>, active: AiSettings) {
        profiles = if (newProfiles.any { it.id == active.id }) newProfiles.toList() else newProfiles + active
        activeId = active.id
        source = active.source
        editing = active
        isNewDraft = false
        dirty = false
        errors = AiFieldErrors()
    }

    private fun loadSource(next: AiSourceId) {
        source = next
        val existing = profilesForSource().firstOrNull()
        if (existing != null) {
            editing = existing
            isNewDraft = false
        } else {
            editing = presetFor(next)
            isNewDraft = true
        }
        dirty = false
        errors = AiFieldErrors()
    }

    private fun presetFor(source: AiSourceId): AiSettings = when (source) {
        AiSourceId.DEEPSEEK_WEBVIEW -> AiSourcePresets.deepseekWebView()
        AiSourceId.GEMINI_WEBVIEW -> AiSourcePresets.geminiWebView()
        AiSourceId.CHATGPT_WEBVIEW -> AiSourcePresets.chatgptWebView()
        AiSourceId.CUSTOM_WEBVIEW -> AiSourcePresets.customWebView()
        AiSourceId.HTTP_API -> AiSourcePresets.httpApiGemini()
    }.copy(id = newId())
}

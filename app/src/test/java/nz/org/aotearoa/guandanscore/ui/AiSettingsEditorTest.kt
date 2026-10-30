package nz.org.aotearoa.guandanscore.ui

import nz.org.aotearoa.guandanscore.recognition.AiSettings
import nz.org.aotearoa.guandanscore.recognition.AiTransport
import nz.org.aotearoa.guandanscore.recognition.source.AiSourceId
import nz.org.aotearoa.guandanscore.recognition.source.AiSourcePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsEditorTest {

    private fun deepSeek(id: String = "ds") = AiSettings(
        id = id,
        sourceKey = AiSourceId.DEEPSEEK_WEBVIEW.key,
        provider = "DeepSeek 网页版",
        homeUrl = "https://chat.deepseek.com",
        transport = AiTransport.WEBVIEW
    )

    private fun gemini(id: String = "gem") = AiSettings(
        id = id,
        sourceKey = AiSourceId.GEMINI_WEBVIEW.key,
        provider = "Gemini 网页版",
        homeUrl = "https://gemini.google.com/app",
        transport = AiTransport.WEBVIEW
    )

    private fun api(id: String = "api") = AiSourcePresets.httpApiGemini().copy(
        id = id,
        provider = "Gemini API",
        apiKey = "test-key"
    )

    private fun editor(
        profiles: List<AiSettings> = listOf(deepSeek(), gemini(), api()),
        active: AiSettings = profiles.first(),
        nextId: String = "new-id"
    ) = AiSettingsEditor(profiles, active) { nextId }

    @Test
    fun sourceOrderMatchesManagementPage() {
        assertEquals(
            listOf(
                AiSourceId.DEEPSEEK_WEBVIEW,
                AiSourceId.GEMINI_WEBVIEW,
                AiSourceId.CHATGPT_WEBVIEW,
                AiSourceId.CUSTOM_WEBVIEW,
                AiSourceId.HTTP_API
            ),
            AiSourceId.entries
        )
    }

    @Test
    fun opensOnActiveProfile() {
        val e = editor()
        assertEquals(AiSourceId.DEEPSEEK_WEBVIEW, e.source)
        assertEquals("ds", e.editing.id)
        assertTrue(e.isEditingActive())
        assertFalse(e.hasUnsavedChanges)
        assertTrue(e.errors.isEmpty)
    }

    @Test
    fun switchingSourceLoadsFirstExistingProfile() {
        val e = editor()
        assertEquals(EditOutcome.Applied, e.selectSource(AiSourceId.GEMINI_WEBVIEW))
        assertEquals("gem", e.editing.id)
        assertFalse(e.isNewDraft)
        assertFalse(e.isEditingActive())
        assertFalse(e.hasUnsavedChanges)
    }

    @Test
    fun switchingToSourceWithoutProfileCreatesNewDraft() {
        val e = editor()
        assertEquals(EditOutcome.Applied, e.selectSource(AiSourceId.CUSTOM_WEBVIEW))
        assertTrue(e.isNewDraft)
        assertTrue(e.hasUnsavedChanges)
        assertEquals(AiSourceId.CUSTOM_WEBVIEW.key, e.editing.sourceKey)
    }

    @Test
    fun switchingWithUnsavedChangesRequiresConfirmation() {
        val e = editor()
        e.update(AiField.NAME, "改过的名称")
        assertTrue(e.hasUnsavedChanges)
        assertEquals(EditOutcome.NeedsConfirmation, e.selectSource(AiSourceId.GEMINI_WEBVIEW))
        assertEquals("ds", e.editing.id)

        assertEquals(EditOutcome.Applied, e.selectSource(AiSourceId.GEMINI_WEBVIEW, force = true))
        assertEquals("gem", e.editing.id)
        assertFalse(e.hasUnsavedChanges)
    }

    @Test
    fun selectingSameProfileDoesNotDiscardEdits() {
        val e = editor()
        e.update(AiField.NAME, "改过的名称")
        assertEquals(EditOutcome.Applied, e.selectProfile("ds"))
        assertEquals("改过的名称", e.editing.provider)
        assertTrue(e.hasUnsavedChanges)
    }

    @Test
    fun selectingAnotherProfileWithEditsRequiresConfirmation() {
        val e = editor()
        e.update(AiField.NAME, "改过的名称")
        assertEquals(EditOutcome.NeedsConfirmation, e.selectProfile("gem"))
        assertEquals(EditOutcome.Applied, e.selectProfile("gem", force = true))
        assertEquals("gem", e.editing.id)
        assertFalse(e.hasUnsavedChanges)
    }

    @Test
    fun newProfileUsesInjectedIdAndIsUnsaved() {
        val e = editor()
        assertEquals(EditOutcome.Applied, e.newProfile())
        assertEquals("new-id", e.editing.id)
        assertTrue(e.isNewDraft)
        assertTrue(e.hasUnsavedChanges)
        assertFalse(e.isEditingActive())
    }

    @Test
    fun buildForSaveTrimsAndClearsHiddenFieldsOnWeb() {
        val e = editor()
        e.update(AiField.NAME, "  自定义名称  ")
        e.update(AiField.HOME_URL, "  https://chat.deepseek.com  ")
        e.update(AiField.ENDPOINT, "https://leftover.invalid")
        e.update(AiField.MODEL, "leftover")
        e.update(AiField.API_KEY, "leftover-key")

        val saved = e.buildForSave()
        assertEquals("自定义名称", saved.provider)
        assertEquals("https://chat.deepseek.com", saved.homeUrl)
        assertEquals("", saved.endpoint)
        assertEquals("", saved.model)
        assertEquals("", saved.apiKey)
        assertEquals(AiTransport.WEBVIEW, saved.transport)
        assertEquals(AiSourceId.DEEPSEEK_WEBVIEW.key, saved.sourceKey)
    }

    @Test
    fun buildForSaveFallsBackToSourceLabelAndClearsWebFieldsOnApi() {
        val e = editor(active = api())
        e.update(AiField.NAME, "   ")
        e.update(AiField.HOME_URL, "https://leftover.invalid")
        e.update(AiField.ENDPOINT, "  https://api.example.test/v1  ")
        e.update(AiField.MODEL, "  model-x  ")
        e.update(AiField.API_KEY, "  secret  ")

        val saved = e.buildForSave()
        assertEquals(AiSourceId.HTTP_API.label, saved.provider)
        assertEquals("", saved.homeUrl)
        assertEquals("https://api.example.test/v1", saved.endpoint)
        assertEquals("model-x", saved.model)
        assertEquals("secret", saved.apiKey)
        assertEquals(AiTransport.API, saved.transport)
        assertEquals(AiSourceId.HTTP_API.key, saved.sourceKey)
    }

    @Test
    fun validationErrorsAreStoredAndClearedPerField() {
        val e = editor()
        e.update(AiField.HOME_URL, "")
        val errors = e.validate()
        assertEquals("网页源必须填写首页地址", errors.message(AiField.HOME_URL))
        assertEquals(errors, e.errors)

        e.update(AiField.HOME_URL, "https://chat.deepseek.com")
        assertNull(e.errors.message(AiField.HOME_URL))
    }

    @Test
    fun deletePlanRejectsNewDraftAndLastProfile() {
        val draft = editor().apply { newProfile(force = true) }
        assertFalse(draft.deletePlan().canDelete)
        assertTrue(draft.deletePlan().reason!!.contains("尚未保存"))

        val only = editor(profiles = listOf(deepSeek()), active = deepSeek())
        assertFalse(only.deletePlan().canDelete)
        assertEquals("至少保留一个配置档", only.deletePlan().reason)
    }

    @Test
    fun deletePlanFallsBackToSameSourceFirst() {
        val secondDs = deepSeek(id = "ds-2")
        val e = editor(profiles = listOf(deepSeek(), secondDs, gemini()), active = deepSeek())
        val plan = e.deletePlan()
        assertTrue(plan.canDelete)
        assertEquals("ds-2", plan.fallback?.id)
        assertNull(plan.reason)
    }

    @Test
    fun deletePlanFallsBackToFirstProfileOfAnotherSource() {
        val e = editor(profiles = listOf(deepSeek(), api()), active = deepSeek())
        val plan = e.deletePlan()
        assertTrue(plan.canDelete)
        assertEquals("api", plan.fallback?.id)
    }

    @Test
    fun restoreDraftKeepsFieldsWithoutTouchingProfiles() {
        val e = editor()
        val draft = e.editing.copy(provider = "草稿名称", homeUrl = "https://draft.test")
        e.restoreDraft(draft, newDraft = false, modified = true)
        assertEquals("草稿名称", e.editing.provider)
        assertEquals("https://draft.test", e.editing.homeUrl)
        assertTrue(e.hasUnsavedChanges)
        assertEquals(3, e.profiles.size)
    }

    @Test
    fun reloadAfterDeleteFollowsPersistedActive() {
        val e = editor()
        e.reload(listOf(deepSeek(), gemini()), gemini())
        assertEquals("gem", e.editing.id)
        assertEquals(AiSourceId.GEMINI_WEBVIEW, e.source)
        assertEquals("gem", e.activeId)
        assertFalse(e.hasUnsavedChanges)
    }
}

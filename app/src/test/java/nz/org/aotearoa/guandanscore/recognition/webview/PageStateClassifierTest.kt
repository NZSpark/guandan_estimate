package nz.org.aotearoa.guandanscore.recognition.webview

import nz.org.aotearoa.guandanscore.recognition.site.DiagnoseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PageStateClassifier] 状态分类与处置动作单测（AI-031）。纯 JVM。 */
class PageStateClassifierTest {

    private fun diag(
        href: String = "https://example.invalid/chat",
        readyState: String = "complete",
        innerHeight: Int = 800,
        inputCount: Int = 1,
        loginIndicatorCount: Int = 0,
        passwordCount: Int = 0
    ) = DiagnoseResult(
        href = href,
        readyState = readyState,
        innerHeight = innerHeight,
        inputCount = inputCount,
        sendCount = 1,
        responseCount = 0,
        fileInputCount = 1,
        loginIndicatorCount = loginIndicatorCount,
        generatingCount = 0,
        passwordCount = passwordCount
    )

    @Test
    fun healthyPageIsReady() {
        assertEquals(PageState.READY, PageStateClassifier.classify(diag()))
    }

    @Test
    fun blankHrefIsLoading() {
        // 冷启动：文档尚未 commit，origin 为空。
        assertEquals(PageState.LOADING, PageStateClassifier.classify(diag(href = "")))
        assertEquals(PageState.LOADING, PageStateClassifier.classify(diag(href = "about:blank")))
    }

    @Test
    fun loadingReadyStateIsLoadingEvenWithHref() {
        // href 已有但 readyState 仍是 loading：不能当可操作。
        assertEquals(PageState.LOADING, PageStateClassifier.classify(diag(readyState = "loading")))
    }

    @Test
    fun passwordFieldMeansLoginRequired() {
        // 密码框 + 输入框并存时，登录优先于其他判断。
        assertEquals(
            PageState.LOGIN_REQUIRED,
            PageStateClassifier.classify(diag(passwordCount = 1))
        )
    }

    @Test
    fun loginUiWithoutComposerMeansLoginRequired() {
        assertEquals(
            PageState.LOGIN_REQUIRED,
            PageStateClassifier.classify(diag(inputCount = 0, loginIndicatorCount = 1))
        )
    }

    @Test
    fun accountMenuDoesNotLookLikeLoginWhenComposerExists() {
        // M6：已登录页有账户菜单，但输入框在，不能被判成未登录。
        assertEquals(
            PageState.READY,
            PageStateClassifier.classify(diag(inputCount = 1, loginIndicatorCount = 1))
        )
    }

    @Test
    fun collapsedViewportIsHostProblem() {
        // 视口塌陷不是页面问题，必须先于选择器判断。
        assertEquals(
            PageState.VIEWPORT_UNHEALTHY,
            PageStateClassifier.classify(diag(innerHeight = 0, inputCount = 0))
        )
    }

    @Test
    fun missingComposerIsComposerMissing() {
        assertEquals(
            PageState.COMPOSER_MISSING,
            PageStateClassifier.classify(diag(inputCount = 0))
        )
    }

    @Test
    fun loginUrlBeatsComposerMissing() {
        // 真机缺陷回归：未登录被重定向到 /sign_in，DOM 里 inputCount=0、passwordCount=0。
        // 若按选择器缺失处理会 RELOAD，而重载只会再次落到同一登录页，形成空转。
        assertEquals(
            PageState.LOGIN_REQUIRED,
            PageStateClassifier.classify(diag(inputCount = 0), urlIsLoginPage = true)
        )
        assertEquals(
            PageAction.NEEDS_USER,
            PageStateClassifier.actionFor(
                PageStateClassifier.classify(diag(inputCount = 0), urlIsLoginPage = true)
            )
        )
    }

    @Test
    fun loginUrlFlagDefaultsToFalse() {
        // 不传该参数时行为必须与旧版一致，避免影响既有调用方。
        assertEquals(
            PageState.COMPOSER_MISSING,
            PageStateClassifier.classify(diag(inputCount = 0))
        )
    }

    @Test
    fun loginBeatsViewportAndComposer() {
        // 顺序契约：登录 → 视口 → 选择器。三者同时异常时必须是 LOGIN_REQUIRED。
        assertEquals(
            PageState.LOGIN_REQUIRED,
            PageStateClassifier.classify(diag(passwordCount = 1, innerHeight = 0, inputCount = 0))
        )
    }

    @Test
    fun actionsMapByRetryability() {
        assertEquals(PageAction.PROCEED, PageStateClassifier.actionFor(PageState.READY))
        assertEquals(PageAction.RETRY, PageStateClassifier.actionFor(PageState.LOADING))
        assertEquals(PageAction.RELOAD, PageStateClassifier.actionFor(PageState.COMPOSER_MISSING))
        // 这两种重试无用，必须停下来求人。
        assertEquals(PageAction.NEEDS_USER, PageStateClassifier.actionFor(PageState.VIEWPORT_UNHEALTHY))
        assertEquals(PageAction.NEEDS_USER, PageStateClassifier.actionFor(PageState.LOGIN_REQUIRED))
    }

    @Test
    fun reasonIsEmptyOnlyForReady() {
        assertTrue(PageStateClassifier.reason(PageState.READY, diag()).isEmpty())
        assertTrue(PageStateClassifier.reason(PageState.LOGIN_REQUIRED, diag()).contains("登录"))
        assertTrue(
            PageStateClassifier.reason(PageState.COMPOSER_MISSING, diag(inputCount = 0))
                .contains("inputCount=0")
        )
        assertTrue(
            PageStateClassifier.reason(PageState.VIEWPORT_UNHEALTHY, diag(innerHeight = 0))
                .contains("innerHeight=0")
        )
    }

    @Test
    fun reasonToleratesNullDiagnose() {
        // 诊断本身失败时不能抛，得给出可读原因。
        assertTrue(PageStateClassifier.reason(PageState.LOADING, null).isNotEmpty())
        assertTrue(PageStateClassifier.reason(PageState.COMPOSER_MISSING, null).isNotEmpty())
    }
}

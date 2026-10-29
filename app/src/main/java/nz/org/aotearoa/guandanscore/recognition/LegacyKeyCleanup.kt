package nz.org.aotearoa.guandanscore.recognition

/**
 * AI-094：旧 Gemini API Key 的有序清理。
 *
 * ## 为什么需要单独一层
 * 「清理 Key」如果直接写在 `AiSettingsStore` 里，就只能靠真机手工验证；而这里出错的
 * 代价很高 —— 误删会让用户重填、漏删会让旧凭据长期驻留。因此把**判定**抽成纯函数，
 * 由 `AiSettingsStore` 只负责执行，单测覆盖判定。
 *
 * ## 清理规则
 * 1. 只有 **网页源**（`transport == WEBVIEW`）的 `apiKey` 该被清空：网页源根本不读它。
 * 2. `HTTP_API` 源的 Key **必须保留** —— 那是它唯一可用的凭据，误删会直接打断调用。
 * 3. 清理必须**幂等**：已经是空的 profile 原样返回，不产生新对象，避免无谓写盘。
 * 4. Keystore alias 只在「**没有任何 profile 再用 Key**」时才删除；只要还有 API 源在，
 *    alias 就是活的，删掉会让现存密文永久无法解密（比留着旧 Key 更糟）。
 *
 * ## 不做的事
 * - 不打印、不记录 Key 的任何片段（包括长度）。日志里只出现 profile id。
 * - 不改 `sourceKey` / `transport` / `homeUrl`，那是 AI-137 迁移的职责，两件事不能混。
 */
object LegacyKeyCleanup {

    /** 清理结果。[changed] 为 false 时调用方应跳过写盘。 */
    data class Plan(
        val profiles: List<AiSettings>,
        val changed: Boolean,
        /** 清理后仍持有 Key 的 profile id，用于日志与判断 alias 是否可删。 */
        val retainingIds: List<String>,
        /** 被清空的 profile id。 */
        val clearedIds: List<String>
    ) {
        /** 是否还有源需要 Keystore alias（有 API 源就必须保留）。 */
        val keepsKeystoreAlias: Boolean get() = retainingIds.isNotEmpty()
    }

    /**
     * 计算清理计划。
     *
     * @param profiles 当前全部 profile
     */
    fun plan(profiles: List<AiSettings>): Plan {
        val cleared = mutableListOf<String>()
        val retaining = mutableListOf<String>()
        val out = profiles.map { s ->
            val web = s.transport == AiTransport.WEBVIEW
            val shouldClear = web && s.apiKey.isNotEmpty()
            when {
                shouldClear -> {
                    cleared += s.id
                    s.copy(apiKey = "")
                }
                s.apiKey.isNotEmpty() -> {
                    retaining += s.id
                    s
                }
                else -> s
            }
        }
        return Plan(
            profiles = out,
            changed = cleared.isNotEmpty(),
            retainingIds = retaining,
            clearedIds = cleared
        )
    }

    /**
     * 是否可以删除 Keystore alias。
     *
     * 只有「没有任何 profile 还持有 Key」时才可删。否则现存密文会变成永远解不开的垃圾，
     * 用户看到的是「Key 还在但每次调用都失败」—— 比不清理更隐蔽。
     */
    fun canDropKeystoreAlias(profiles: List<AiSettings>): Boolean =
        profiles.none { it.apiKey.isNotEmpty() }
}

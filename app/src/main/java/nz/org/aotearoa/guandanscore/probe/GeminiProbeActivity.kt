package nz.org.aotearoa.guandanscore.probe

/**
 * 旧入口（阶段 0 时代）。保留类名与 adb 命令兼容，实现已全部上移到 [AiProbeActivity]。
 *
 * ```
 * adb shell am start -n nz.org.aotearoa.guandanscore/.probe.GeminiProbeActivity
 * adb shell am start -n nz.org.aotearoa.guandanscore/.probe.GeminiProbeActivity \
 *   --es start_url https://chat.deepseek.com
 * ```
 *
 * @see AiProbeActivity
 */
class GeminiProbeActivity : AiProbeActivity()

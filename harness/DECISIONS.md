# Decisions —— 关键决策记录

> 追加式记录，每条含日期、决策、理由、状态。
> 旧决策不删除，被取代时标注 `Superseded by ADR-00xx`，由 Cleanup 归档。

## ADR-0001 · 2026-10-28 · 采用单一真源 + 轻量工具适配的 Harness 结构

**决策**：以 `AGENTS.md` 作为唯一入口与共享知识根，`CLAUDE.md` 只做一行引用；记忆分层放在 `harness/`（CRITICAL / state / DECISIONS / index / memory / archive），可复用工作流放在 `skills/`。

**理由**：仓库此前完全没有 AI 入口文件，无历史包袱；评分规则已有两份 Markdown 文档，Harness 不应复制公式表，只做指针与路由，避免双份规则漂移。

**状态**：Active

---

## ADR-0002 · 2026-10-28 · Harness 不复制评分公式，只引用原始文档

**决策**：`harness/memory/scoring-rules.md` 记录“公式在哪里、改动要同步哪些文件、有哪些易错点”，公式本体仍以 `doc/掼蛋牌型量化评分方案.md` / `doc/手牌综合牌力评估方法.md` 为唯一来源。

**理由**：公式表体量大、变更频繁，复制即产生漂移风险；单测已是数值行为的可执行规格。

**状态**：Active

---

## ADR-0003 · 2026-10-28 · Cleanup 采用「任务收尾轻量清理 + 手动深度清理」双档

**决策**：不引入定时调度依赖。日常在任务写回 `state.md` 时顺带做轻量清理（去重、压缩、修索引）；深度清理通过 `skills/harness-cleanup` 手动触发，触发条件为：`CRITICAL.md` > 60 行、`state.md` > 80 行、`memory/` 单文件 > 120 行、或每累计 10 次提交。

**理由**：项目为单人维护的 Android 仓库，无 CI 常驻 agent；过度自动化收益低、维护成本高。

**状态**：Active

---

## ADR-0004 · 2026-10-28 · 归档而非删除

**决策**：过期知识移入 `harness/archive/`，文件名保留原相对路径语义（如 `archive/memory-scoring-rules-2026-10.md`），并在文件头写明归档原因、替代文件与日期。

**理由**：评分规则历史版本对复盘与回归有价值；删除不可逆，且违反“关键决策可追溯”要求。

**状态**：Active

---

## ADR-0005 · 2026-10-28 · WebView 迁移采用「接口隔离 + 纯逻辑可测」分层

**决策**：把 Gemini 网页迁移拆成四层——`AiCardResponseParser`（解析）、`GenerationWatcher`（结束判定）、`GeminiUpstream`（页面能力接口）、`AiRecognitionService`（编排/互斥/错误映射）。前三层零 Android 依赖，可纯 JVM 单测；只有 `GeminiUpstream` 的真实实现依赖 WebView。

**理由**：任务清单的核心风险（上传不可验证、回答串线、DOM 变化）无法离线消除，但可以把「不可测的部分」压缩到最薄一层。这样真机调试时只需替换一个实现，状态机行为已有 60+ 测试保护。

**状态**：Active

---

## ADR-0006 · 2026-10-28 · 选择器集中管理且显式标注「未验证」

**决策**：`GeminiSelectors` 为唯一选择器来源，每个候选值必须真机验证后才能去掉注释中的警告；`GeminiScripts` 中所有动态参数强制经 `JSONObject.quote` 编码，所有脚本返回 `ok`/`why` 结构化结果。

**理由**：任务清单明确禁止把其他项目的选择器当既定事实；同时防止脚本注入与「派发成功即上传成功」的误判。

**状态**：Active

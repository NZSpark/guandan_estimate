---
name: harness-cleanup
description: 清理并整合本仓库 Agent Harness 的记忆文件（harness/、skills/）。当 CRITICAL.md 超过 60 行、state.md 超过 80 行、任一 memory 文件超过 120 行、累计约 10 次提交后，或用户要求「整理记忆 / 去重 / 归档 / 压缩上下文 / cleanup」时使用。执行过时检测、去重、整合、归档、冲突检查、核心约束精简、状态压缩与索引维护。
---

# Harness Cleanup

## 适用场景

- 用户说：整理记忆、去重、压缩、归档、清理 harness、上下文太长。
- 触发阈值：`CRITICAL.md` > 60 行；`state.md` > 80 行；单个 `memory/*.md` > 120 行；L0 合计（AGENTS + CRITICAL + state + index）> 250 行；或每累计约 10 次提交。

## 输入

- `AGENTS.md`、`harness/**`、`skills/**`（只读扫描）。
- `git log --oneline -20`（判断哪些状态已过期）。

## 输出

- 就地更新的 `harness/*.md`、`harness/memory/*.md`。
- 新增 `harness/archive/<原名>-<YYYY-MM>.md`（含归档头）。
- 一份变更摘要（写入 `harness/state.md` 的「最近清理」小节，覆盖旧内容，不追加流水）。

## 步骤

1. **快照**：`git status --short` 确认工作区状态；有未提交改动时先说明，不擅自 stash。
2. **规模体检**：`wc -l AGENTS.md CLAUDE.md harness/*.md harness/memory/*.md skills/*/SKILL.md`，标出超阈值文件。
3. **过时检测**：对每条规则/状态问「是否仍适用于当前 `main`/当前分支代码」。对照 `git log` 与源码，标记已被取代的条目。
4. **冲突检查**：交叉比对 `CRITICAL.md`、`memory/*`、`AGENTS.md`、原始文档。能由新旧关系判定的，标注 `Superseded by …`；无法判定的**列出来问用户**，不擅自改。
5. **去重与整合**：同一事实只留一处（优先留在更权威层级）；把零散条目合并成结构化小节；删除纯过程性描述。
6. **归档**：不再频繁加载但有历史价值的内容移入 `harness/archive/`，文件头写：

   ```markdown
   > ARCHIVED 2026-10
   > 原因：被 <替代文件/决策> 取代
   > 原位置：harness/memory/xxx.md
   ```

7. **核心精简**：`CRITICAL.md` 逐条问「违反了会不会真的出错」，否则降级到 `memory/`。
8. **状态压缩**：`state.md` 只保留当前分支、当前任务进度、阻塞、下一步、已知问题；已完成的历史任务删除。
9. **索引维护**：更新 `harness/index.md` 的路由表与知识地图；`grep -rn "archive/"` 与 `grep -rn "harness/"` 检查断链。
10. **写回摘要**：在 `state.md` 记录本次清理的变更摘要（改了哪些文件、归档了什么、原因）。
11. **复验**：

    ```bash
    wc -l AGENTS.md harness/*.md harness/memory/*.md
    grep -rn "](\\.\\./\\.\\./" harness skills   # 检查相对链接
    ./gradlew testDebugUnitTest                      # 仅在本次改动触及代码/测试时需要
    ```

## 硬规则

- **只合并、压缩、归档，不删除**；`DECISIONS.md` 条目永不删除。
- 涉及核心约束、重要决策、有歧义的信息：**先问用户**，不做不可逆修改。
- 一次清理只动一个层级主题，避免大范围重写导致 review 困难。
- 不为 Cleanup 自身引入脚本或复杂机制；保持纯 Markdown 操作。

## 验收标准

- [ ] L0 合计 ≤ 250 行，`CRITICAL.md` ≤ 60 行，`state.md` ≤ 80 行。
- [ ] `harness/index.md` 中所有路径存在，无断链。
- [ ] 无两处描述同一规则且措辞不一致。
- [ ] `harness/archive/` 下每个文件都有归档头。
- [ ] `state.md` 的「最近清理」反映了本次变更。

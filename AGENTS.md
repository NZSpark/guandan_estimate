# Agent 入口（工具无关，唯一真源）

本仓库是一套模块化、文件驱动、跨工具兼容的 Agent Harness。
**所有 AI 工具（Claude Code、Codex、pi 等）共享本文件与 `harness/`、`skills/` 下的知识，禁止为单个工具另建平行知识库。**

## 启动协议（新会话必读，按顺序，只读必要文件）

1. `harness/CRITICAL.md` —— 不可违反的约束（约 1 屏）。
2. `harness/state.md` —— 当前任务、进度、阻塞、下一步。
3. `harness/index.md` —— 任务路由表；按任务类型再按需读取。

不要一次性读取全部文档。仅在路由表指向时读取 `harness/DECISIONS.md`、`harness/memory/*`、`skills/*`。

## 工具适配层

| 工具 | 适配文件 | 说明 |
| --- | --- | --- |
| pi / 通用 | `AGENTS.md`（本文件） | 入口 |
| Claude Code | `CLAUDE.md` | 仅一行引用本文件，不复制内容 |
| Codex | `AGENTS.md` | 直接使用 |

## 写入规则

- 任务完成后必须写回 `harness/state.md`（就地更新，不无限追加）。
- 影响架构/评分规则/接口契约的决策写 `harness/DECISIONS.md`（追加带日期条目）。
- 长期知识写 `harness/memory/`，过期内容由 Cleanup 移入 `harness/archive/`。
- 同一文件被多工具并发修改前，先 `git status` 确认无未提交冲突。
- 不把临时过程、一次性调试输出写入核心记忆。

## 验证基线

提交前最低验证：

```bash
./gradlew testDebugUnitTest
```

涉及规则文档时，同步核对 `README.md` 与两份评分文档的一致性。

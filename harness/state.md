# State —— 当前状态

> 只保留恢复工作所必需的信息。任务完成即就地改写，不追加历史流水。
> 最后更新：2026-10-28

## 当前分支

`airecognize`（从 `main` 创建）

## 当前任务

无进行中任务。上一任务：建立模块化 Agent Harness 框架 —— 已完成。

## 最近完成

2026-10-28 · Harness 框架落地（ADR-0001 ~ ADR-0004）

- `AGENTS.md`：唯一入口，启动协议 / 工具适配 / 写入规则 / 验证基线。
- `CLAUDE.md`：仅引用 `AGENTS.md`。
- `harness/`：`CRITICAL.md`（16 条硬约束）、`state.md`、`DECISIONS.md`（4 条 ADR）、`index.md`（路由表 + Token 预算）、`archive/README.md`。
- `harness/memory/`：`project-overview.md`、`scoring-rules.md`、`recognition-pipeline.md`、`build-and-test.md`。
- `skills/`：`harness-cleanup`、`add-scoring-test`、`ai-recognition-debug`。
- `README.md`：新增「Agent Harness」小节。

验证：L0 合计 173 行（预算 250）；各文件均在阈值内；`./gradlew testDebugUnitTest` BUILD SUCCESSFUL。

## 阻塞

无。

## 下一步（候选，按需取用）

1. 把 `skills/` 安装到各工具的技能目录（如 `~/.pi/agent/skills/`）以便自动触发。
2. 补 `statistic.py` 的版本对齐说明或标注其为 V3.0 历史脚本。
3. 首次累计 10 次提交后执行一次 `skills/harness-cleanup` 深度清理。

## 已知问题

- 本机无系统 JDK，需 `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`（已记入 `memory/build-and-test.md`）。
- `model_training/install_model.ps1` 仅 PowerShell；macOS 需手动复制模型文件。

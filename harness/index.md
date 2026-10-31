# Index —— 任务路由表

> 用法：在 `AGENTS.md` → `CRITICAL.md` → `state.md` 之后读本表，**只**打开命中的文件。
> 未命中任何路由时，先读 `harness/memory/project-overview.md` 再决定。

## 按任务路由

| 任务 | 必读 | 按需 | Skill |
| --- | --- | --- | --- |
| 修改牌型分值 / SPI / NSPI / 评级阈值 | `doc/掼蛋牌型量化评分方案.md`、`doc/手牌综合牌力评估方法.md` | `memory/scoring-rules.md`、`README.md` 牌力指标节 | `skills/add-scoring-test` |
| 排查识图 / AI Provider / 模板匹配 | `memory/recognition-pipeline.md` | `recognition/` 四个文件、`testcases/` | `skills/ai-recognition-debug` |
| 修改 AI 源管理 / 配置档 / 健康检查 | `doc/ai_configure.md` | `ui/AiSettings*`、`recognition/source/HealthCheckGate.kt`、`doc/multi_ai_update.md` | — |
| 修改拍照取图 / 图片渲染 / 拆牌图 | `memory/project-overview.md` | `imaging/`、`MainActivity.kt`、`res/layout/activity_main.xml` | — |
| 实现手工拆牌 / 分区编辑 / 最优对照 | `doc/patterns_tasks.md` | `doc/patterns_design.md`、`doc/ui_design.md` §5.4、`scoring/` | — |
| 训练 / 接入离线牌角模型 | `model_training/README.md` | `memory/recognition-pipeline.md`、`model_training/*.py` | `skills/ai-recognition-debug` |
| 构建、跑测试、出 APK | `memory/build-and-test.md` | `README.md` 构建环境节 | — |
| 理解项目全貌 / 新人接手 | `memory/project-overview.md` | `README.md` | — |
| 整理记忆、去重、归档 | 本文件、`DECISIONS.md` | `archive/README.md` | `skills/harness-cleanup` |
| 新增 Skill 或修改 Harness 结构 | `DECISIONS.md`、本文件 | — | `skills/harness-cleanup`（收尾） |

## 知识地图

```
AGENTS.md                 入口：启动协议、工具适配、写入规则
harness/
├── CRITICAL.md           硬约束（16 条）
├── state.md              当前任务与进度（每次任务收尾更新）
├── DECISIONS.md          架构/规则决策 ADR
├── index.md              本文件：路由表
├── memory/
│   ├── project-overview.md    模块地图、关键类职责、数据流
│   ├── scoring-rules.md       评分规则改动指南（指针 + 易错点）
│   ├── recognition-pipeline.md 识别管线与 AI Provider 约束
│   └── build-and-test.md      构建/测试/排障
└── archive/              过期知识与历史决策（只读参考）
skills/
├── harness-cleanup/      记忆清理与整合
├── add-scoring-test/     评分规则变更的标准流程
└── ai-recognition-debug/ 识别与 Provider 问题诊断
```

## 原始文档（权威来源，改动必须同步）

| 文件 | 权威内容 |
| --- | --- |
| `doc/掼蛋牌型量化评分方案.md` | 单牌型分值公式、基础点数表、炸弹/同花顺/火箭分值 |
| `doc/手牌综合牌力评估方法.md` | SPI / NSPI / 动态评级 / 散牌惩罚 |
| `doc/tasks.md` | 任务总索引：专项清单、当前进度、真机基线与硬约束 |
| `README.md` | 面向用户的功能说明、指标摘要、构建与隐私声明 |
| `model_training/README.md` | 标注、训练、导出、接入流程 |

## Token 预算

| 层级 | 文件 | 预算 |
| --- | --- | --- |
| L0 启动 | `AGENTS.md` + `CRITICAL.md` + `state.md` + `index.md` | ≤ 250 行合计 |
| L1 路由 | 单篇 `memory/*` | ≤ 120 行 |
| L2 按需 | 原始文档、源码 | 按需读取，优先用 `grep` 定位后局部读 |

超预算即触发 `skills/harness-cleanup` 的上下文优化清理。

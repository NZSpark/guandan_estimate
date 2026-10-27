---
name: add-scoring-test
description: 掼蛋牌型分值 / SPI / NSPI / 评级规则变更的标准流程。当需要修改 HandScorer 的分值公式、炸弹与同花顺分值、控制力系数、散牌惩罚、T_max 或评级阈值，或需要为新牌型补充单元测试时使用。确保文档、代码、测试、README 四者同步。
---

# 评分规则变更与补测

## 适用场景

- 修改 `scoring/HandScorer.kt` 的任何分值/系数/阈值计算。
- 两份评分文档发布新版本（V4.0 → V5.0 等）。
- 发现代码与文档不一致，需要判定谁对。
- 补 `app/src/test/.../scoring/` 的覆盖。

## 输入

- `掼蛋牌型量化评分方案.md`、`手牌综合牌力评估方法.md`（权威）。
- `harness/memory/scoring-rules.md`（改动指南与不变量）。
- `app/src/main/java/nz/org/aotearoa/guandanscore/scoring/HandScorer.kt`。

## 输出

- 更新后的文档（若规则本身变更）。
- 更新后的 `HandScorer.kt`。
- 新增/修改的单元测试。
- 同步的 `README.md` 指标节。
- `harness/state.md` 写回；规则版本变更时追加 `DECISIONS.md`。

## 步骤

1. **定责**：先判断是「规则变了」还是「代码错了」。规则变更必须先改文档或取得用户确认，禁止直接改代码让测试变绿。
2. **读不变量**：对照 `memory/scoring-rules.md` 的「已知不变量」，确认改动不破坏大小关系链与 191/192/1000 边界。
3. **先写测试**：在语义最接近的现有测试文件中加入新用例（`ScoringV4Test` 用于公式边界，`DynamicHandEvaluationTest` 用于 T_max/评级，`PartialHandScorerTest` 用于 1~26 张，`Case2Test`/`Case3Test` 用于文档示例）。断言用整数。
4. **跑失败**：`./gradlew testDebugUnitTest --tests 'nz.org.aotearoa.guandanscore.scoring.*'`，确认新用例如预期失败。
5. **改实现**：最小改动 `HandScorer.kt`，不顺手重构无关逻辑。
6. **跑全绿**：同上命令；再跑全量 `./gradlew testDebugUnitTest`。
7. **同步 README**：公式、上限、α 取值、惩罚公式、三档评级阈值表。
8. **写回**：`harness/state.md` 记录改动；规则版本升级追加 ADR 到 `DECISIONS.md`（含旧版本 → 新版本、影响范围、取代关系）。

## 必测清单（改动涉及则必须覆盖）

- [ ] 普通牌型线性映射下界与上界（最高 191）。
- [ ] 最小四张炸弹 = 192，且严格高于所有普通牌型。
- [ ] 5 张炸弹 vs 同花顺、同花顺 vs 6 张炸弹的顺序。
- [ ] 6 张及以上炸弹的对数平滑（非线性）。
- [ ] 火箭固定 1000。
- [ ] 级牌在 单/对/三/炸、顺子、连对/钢板/三带二 三种语义下的取值。
- [ ] A 在 A2345 中记 1，其余记 14。
- [ ] 散牌惩罚 `P=15×N_waste`，`N<10` 时为 0。
- [ ] NSPI 使用 `T_max(N)`，SPI 使用方案 `T_plan`，两者不混。
- [ ] 动态评级三档边界值（等于阈值时的归属）。

## 硬规则

- 不得通过放宽断言、加 `delta` 容差、跳过测试来“修复”失败。
- 不得同时改公式与阈值来凑一个目标 SPI。
- 规则冲突且无法判定时，停下来问用户，不猜。

## 验收标准

- [ ] 全量 `./gradlew testDebugUnitTest` 通过。
- [ ] 文档、`HandScorer`、测试、README 数值一致。
- [ ] 新增用例能在回退实现后失败（验证测试有效性）。
- [ ] `state.md` 已更新；版本变更已记入 `DECISIONS.md`。

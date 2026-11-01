# 人工签核目录

`doc/local_engine_tasks.md` §7.6 要求：程序只能检查签核是否存在、格式是否正确、绑定 hash 是否一致，
**不能代签**。缺签核时相关任务状态为 `BLOCKED`（退出码 4）。

| 文件 | 对应任务 | 必需字段 |
| --- | --- | --- |
| `golden-definition.json` | T0.5、T1.5 | `images`、`reviewer`、`reviewed_at`、`version` |
| `photo-visibility.json` | T1.2、T3.1、T5.1 | `images`、`reviewer`、`reviewed_at`、`version` |
| `correction-approval.json` | T5.1 | `corrections`、`reviewer`、`reviewed_at`、`version` |
| `release-qa.json` | T5.3、T5.4 | `release`、`test_report`、`device_report`、`license_report`、`rollback`、`reviewer`、`reviewed_at`、`version` |

`dataset-license` 签核已于 2026-11-02 按用户要求删除：数据许可只登记，不阻断数据加入。

## 示例（golden 真值确认）

```json
{
  "version": 1,
  "images": [
    {"path": "testcases/case4.jpg", "sha256": "<golden.py prepare 记录的值>", "scene_id": "golden-case4"}
  ],
  "reviewer": "Spark",
  "reviewed_at": "2026-11-01T10:00:00+13:00",
  "notes": "确认 27 张牌面与 Joker 归属"
}
```

`golden.py prepare` 会把图片 SHA-256 写进 `model_training/datasets/golden_truth.json`；
签核文件里的 hash 必须与之一致，否则 `gate.py --require-valid-approvals` 判定失效。

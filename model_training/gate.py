"""门禁汇总（T1.5 子门禁、T5.3 发布门禁、X3 状态汇总）。

三种用法：

1. 单任务门禁：`gate.py --task T1.5 --report RUN/report.json --require-approval golden-definition`
2. 发布门禁：`gate.py --task-manifest model_training/task_manifest.json --reports output/runs --require-all-tasks`
3. 里程碑汇总：`gate.py --milestone all --reports output/runs --state harness/state.md --output-dir RUN`

任何 `FAIL` 优先于 `BLOCKED`，`BLOCKED` 优先于 `NOT_RUN`；只有全部 PASS 才返回 0。
汇总不会把 NOT_RUN/BLOCKED 自动改写成完成，也不会改动 harness/state.md（只生成建议稿）。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from _cli import (
    EXIT_APPROVAL,
    EXIT_ENV,
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_BLOCKED,
    STATUS_FAIL,
    STATUS_NOT_RUN,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    load_approvals,
    make_report,
    prepare,
    read_json,
    rel,
    resolve_path,
    run,
    sha256_file,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "X3"

SEVERITY = {STATUS_PASS: 0, STATUS_NOT_RUN: 1, STATUS_BLOCKED: 2, STATUS_FAIL: 3}


def scan_reports(root: Path) -> dict[str, dict]:
    """每个 task_id 取最新的一份 report.json（按 finished_at）。"""
    latest: dict[str, dict] = {}
    for path in sorted(root.rglob("report.json")):
        try:
            payload = read_json(path)
        except CliError:
            continue
        task = payload.get("task_id")
        if not task:
            continue
        payload["_path"] = str(path)
        payload["_sha256"] = sha256_file(path)
        current = latest.get(task)
        if current is None or payload.get("finished_at", "") >= current.get("finished_at", ""):
            latest[task] = payload
    return latest


def main() -> int:
    parser = build_parser(
        prog="gate.py",
        version=VERSION,
        task_id=TASK_ID,
        description="汇总任务报告、签核与设备证据，计算门禁状态",
    )
    parser.add_argument("--task", default=None, help="单任务门禁：任务 ID")
    parser.add_argument("--report", action="append", default=[], help="单任务门禁：report.json 路径（可重复）")
    parser.add_argument("--task-manifest", default=None, help="任务清单，用于要求覆盖率")
    parser.add_argument("--reports", default=None, help="报告根目录，递归查找 report.json")
    parser.add_argument("--milestone", default=None, help="all 或 M0..M5")
    parser.add_argument("--state", default=None, help="harness/state.md（只读取，用于生成建议）")
    parser.add_argument("--decisions", default=None, help="harness/DECISIONS.md（不自动追加）")
    parser.add_argument("--require-approval", action="append", default=[], help="必需签核类型（可重复）")
    parser.add_argument("--require-all-tasks", action="store_true")
    parser.add_argument("--require-artifact-hashes", action="store_true")
    parser.add_argument("--require-valid-approvals", action="store_true")
    parser.add_argument("--require-device-api", type=int, default=None)
    parser.add_argument("--ci-report", default=None)
    parser.add_argument("--write-state", action="store_true", help="把建议稿写回 harness/state.md 的汇总段")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, args.task or TASK_ID)
    report.parameters.update(
        task=args.task,
        reports=args.reports,
        milestone=args.milestone,
        require_all_tasks=args.require_all_tasks,
        require_device_api=args.require_device_api,
    )

    def body() -> int:
        collected: dict[str, dict] = {}
        for raw in args.report:
            path = resolve_path(repo_root, raw)
            if path is None or not path.is_file():
                raise CliError(f"报告不存在：{raw}")
            payload = read_json(path)
            payload["_path"] = rel(repo_root, path)
            payload["_sha256"] = sha256_file(path)
            collected[payload.get("task_id", "?")] = payload
            report.add_input(path, "report")

        if args.reports:
            root = resolve_path(repo_root, args.reports)
            if root is None or not root.is_dir():
                raise CliError(f"报告目录不存在：{args.reports}")
            for task, payload in scan_reports(root).items():
                collected.setdefault(task, payload)

        if not collected:
            raise CliError("没有可汇总的报告")

        manifest_tasks: list[str] = []
        if args.task_manifest:
            manifest_path = resolve_path(repo_root, args.task_manifest)
            if manifest_path is None or not manifest_path.is_file():
                raise CliError(f"任务清单不存在：{args.task_manifest}")
            manifest = read_json(manifest_path)
            manifest_tasks = [task["task_id"] for task in manifest.get("tasks", [])]
            report.add_input(manifest_path, "task-manifest")

        rows = []
        for task, payload in sorted(collected.items()):
            status = payload.get("status", STATUS_FAIL)
            detail = {
                "task_id": task,
                "status": status,
                "report": payload.get("_path"),
                "report_sha256": payload.get("_sha256"),
                "metrics": summarise_metrics(payload),
                "errors": (payload.get("errors") or [])[:3],
                "warnings": (payload.get("warnings") or [])[:3],
            }
            if args.require_artifact_hashes:
                missing = [
                    item.get("path")
                    for item in (payload.get("inputs") or []) + (payload.get("outputs") or [])
                    if not item.get("sha256")
                ]
                if missing:
                    detail["missing_hashes"] = missing[:5]
                    status = STATUS_FAIL
                    detail["status"] = STATUS_FAIL
            if args.require_device_api is not None:
                device = payload.get("device") or {}
                if status == STATUS_PASS and device.get("api_level") != args.require_device_api:
                    detail["device_gap"] = f"需要 API {args.require_device_api} 真机证据，实际 {device.get('api_level')}"
                    status = STATUS_NOT_RUN
                    detail["status"] = STATUS_NOT_RUN
            rows.append(detail)

        # 汇总任务本身（X3）在本报告写出后才存在，不计入缺失。
        self_task = args.task or TASK_ID
        missing_tasks = [task for task in manifest_tasks if task not in collected and task != self_task]
        if args.require_all_tasks and missing_tasks:
            report.check("all_tasks_reported", False, f"缺报告：{', '.join(missing_tasks)}")
        elif manifest_tasks:
            report.check("all_tasks_reported", not missing_tasks, f"{len(missing_tasks)} missing")

        approvals = load_approvals(repo_root, args.require_approval) if args.require_approval else []
        if approvals:
            report.approvals = approvals
            valid = [a for a in approvals if a["valid"]]
            report.check("approvals_valid", len(valid) == len(approvals), f"{len(valid)}/{len(approvals)}")

        statuses = [row["status"] for row in rows]
        worst = max(statuses, key=lambda value: SEVERITY.get(value, 3)) if statuses else STATUS_FAIL
        report.check("no_failed_reports", worst != STATUS_FAIL, f"{statuses.count(STATUS_FAIL)} failed")
        report.metrics.update(
            tasks=len(rows),
            pass_count=statuses.count(STATUS_PASS),
            fail_count=statuses.count(STATUS_FAIL),
            blocked_count=statuses.count(STATUS_BLOCKED),
            not_run_count=statuses.count(STATUS_NOT_RUN),
            missing_tasks=missing_tasks,
        )
        report.dataset["sample_count"] = len(rows)

        summary_path = run_dir / "gate-summary.json"
        write_json(summary_path, {"milestone": args.milestone, "rows": rows, "missing_tasks": missing_tasks})
        report.add_output(summary_path, "summary")
        markdown = render_summary(rows, missing_tasks, approvals)
        markdown_path = run_dir / "gate-summary.md"
        markdown_path.write_text(markdown, encoding="utf-8")
        report.add_output(markdown_path, "summary")

        if args.state or args.milestone:
            suggestion = run_dir / "state-update-suggestion.md"
            suggestion.write_text(markdown, encoding="utf-8")
            report.add_output(suggestion, "state-suggestion")
            if args.write_state and args.state:
                report.warn("--write-state 只写汇总段，不会自动把 NOT_RUN/BLOCKED 记为完成")

        if worst == STATUS_FAIL:
            return emit(report, STATUS_FAIL, EXIT_FAIL, args)
        if worst == STATUS_BLOCKED or any(not a["valid"] for a in approvals):
            for item in approvals:
                if not item["valid"]:
                    report.error(f"签核无效：{item['type']}（{item.get('reason', '')}）")
            return emit(report, STATUS_BLOCKED, EXIT_APPROVAL, args)
        if worst == STATUS_NOT_RUN:
            report.warn("存在未执行的任务")
            return emit(report, STATUS_NOT_RUN, EXIT_ENV, args)
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


def summarise_metrics(payload: dict) -> dict:
    metrics = payload.get("metrics") or {}
    keep = {}
    for key in ("exact", "per_card_exact", "recall", "ece", "scenes", "cards", "crops", "distinct_labels"):
        if key in metrics:
            keep[key] = metrics[key]
    return keep


def render_summary(rows: list[dict], missing: list[str], approvals: list[dict]) -> str:
    lines = ["# 门禁汇总", "", "| 任务 | 状态 | 关键指标 | 报告 |", "| --- | --- | --- | --- |"]
    for row in rows:
        metrics = ", ".join(f"{k}={v}" for k, v in row["metrics"].items()) or "—"
        lines.append(f"| {row['task_id']} | {row['status']} | {metrics} | `{row['report']}` |")
    if missing:
        lines += ["", "未产出报告的任务：" + ", ".join(missing)]
    if approvals:
        lines += ["", "| 签核 | 有效 | 说明 |", "| --- | --- | --- |"]
        for item in approvals:
            lines.append(f"| {item['type']} | {'是' if item['valid'] else '否'} | {item.get('reason', '')} |")
    lines += [
        "",
        "> NO_RUN/BLOCKED 不是完成。需要真实设备证据、许可签核与发布 QA 才能推进。",
    ]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    raise SystemExit(main())

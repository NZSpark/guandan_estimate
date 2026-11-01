"""文档与命令一致性检查（T5.4）。

- 校验 doc/local_engine_tasks.md 中提到的每个 Python 入口都存在且 `--help` 可运行。
- 校验 task_manifest.json 覆盖 T0.1–T5.4 与 X1–X3。
- 校验 Markdown 中的相对链接存在。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

from _cli import (
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    make_report,
    prepare,
    read_json,
    resolve_path,
    run,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "T5.4"

EXPECTED_TASKS = (
    [f"T0.{i}" for i in range(1, 6)]
    + [f"T1.{i}" for i in range(1, 6)]
    + [f"T2.{i}" for i in range(1, 6)]
    + [f"T3.{i}" for i in range(1, 6)]
    + [f"T4.{i}" for i in range(1, 8)]
    + [f"T5.{i}" for i in range(1, 5)]
    + ["X1", "X2", "X3"]
)


def script_entries(doc_text: str) -> set[str]:
    return set(re.findall(r"model_training/([a-z_]+\.py)", doc_text))


def main() -> int:
    parser = build_parser(
        prog="validate_docs.py",
        version=VERSION,
        task_id=TASK_ID,
        description="校验文档命令、任务覆盖率与相对链接",
    )
    parser.add_argument("--doc", default="doc/local_engine_tasks.md")
    parser.add_argument("--task-manifest", default="model_training/task_manifest.json")
    parser.add_argument("--check-commands", action="store_true")
    parser.add_argument("--check-task-coverage", action="store_true")
    parser.add_argument("--check-links", action="store_true")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(doc=args.doc, task_manifest=args.task_manifest)

    def body() -> int:
        doc_path = resolve_path(repo_root, args.doc)
        if doc_path is None or not doc_path.is_file():
            raise CliError(f"文档不存在：{args.doc}")
        report.add_input(doc_path, "doc")
        text = doc_path.read_text(encoding="utf-8")
        results: dict[str, dict] = {}

        if args.check_commands:
            scripts = sorted(script_entries(text))
            failures = []
            for name in scripts:
                path = repo_root / "model_training" / name
                if not path.is_file():
                    failures.append({"script": name, "reason": "文件不存在"})
                    continue
                proc = subprocess.run(
                    [sys.executable, str(path), "--help"],
                    capture_output=True,
                    text=True,
                    cwd=str(repo_root),
                )
                if proc.returncode != 0:
                    failures.append({"script": name, "reason": proc.stderr.strip()[:300]})
                results[name] = {"exit": proc.returncode, "help_has_usage": "--usage" in proc.stdout or "usage:" in proc.stdout}
            report.metrics["script_entries"] = len(scripts)
            report.metrics["script_failures"] = failures
            report.check("all_doc_scripts_runnable", not failures, f"{len(failures)}/{len(scripts)} failed")
            cli_path = run_dir / "cli-help.json"
            write_json(cli_path, results)
            report.add_output(cli_path, "cli-help")

        if args.check_task_coverage:
            manifest_path = resolve_path(repo_root, args.task_manifest)
            if manifest_path is None or not manifest_path.is_file():
                raise CliError(f"任务清单不存在：{args.task_manifest}")
            manifest = read_json(manifest_path)
            listed = {task["task_id"] for task in manifest.get("tasks", [])}
            missing = [task for task in EXPECTED_TASKS if task not in listed]
            report.metrics["tasks_in_manifest"] = len(listed)
            report.check("task_manifest_covers_doc", not missing, f"缺：{missing}")
            absent = [(t["task_id"], t["script"]) for t in manifest.get("tasks", []) if not (repo_root / t["script"]).exists()]
            report.metrics["entry_points_missing"] = absent
            report.check(
                "task_manifest_entries_exist",
                not absent,
                f"未实现的入口：{', '.join(f'{task}:{path}' for task, path in absent)}" if absent else "全部入口存在",
            )

        if args.check_links:
            broken = []
            for md in sorted(repo_root.glob("**/*.md")):
                if any(part.startswith(".") or part in {"output", "build", ".venv"} for part in md.parts):
                    continue
                for match in re.finditer(r"\[[^\]]+\]\(([^)#\s]+)\)", md.read_text(encoding="utf-8", errors="ignore")):
                    target = match.group(1)
                    if target.startswith(("http://", "https://", "mailto:")):
                        continue
                    if not (md.parent / target).resolve().exists():
                        broken.append({"doc": str(md.relative_to(repo_root)), "target": target})
            report.metrics["broken_links"] = broken[:20]
            report.check("relative_links_resolve", not broken, f"{len(broken)} broken")

        report.check("checks_selected", any([args.check_commands, args.check_task_coverage, args.check_links]), "至少一个检查项")
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

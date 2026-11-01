"""JVM 单测任务运行器（T0.1–T0.4 等的 gradle 入口包装）。

执行 `./gradlew :app:testDebugUnitTest --tests …`，解析 JUnit XML，产出 §7.3 report.json。
不是新任务，只是把 gradle 入口接进统一报告，避免“命令跑过但没有证据”。
"""

from __future__ import annotations

import argparse
import glob
import os
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

from _cli import (
    EXIT_ENV,
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_NOT_RUN,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    make_report,
    prepare,
    rel,
    run,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "T0.1"


def main() -> int:
    parser = build_parser(
        prog="run_gradle_task.py",
        version=VERSION,
        task_id=TASK_ID,
        description="运行指定 gradle 单测并汇总 JUnit XML 为 report.json",
    )
    parser.add_argument("--task", required=True, help="任务 ID，例如 T0.1")
    parser.add_argument("--tests", action="append", default=[], help="--tests 过滤（可重复）")
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, args.task)

    def body() -> int:
        wrapper = repo_root / "gradlew"
        if not wrapper.is_file():
            raise CliError("找不到 gradlew")
        java_home = os.environ.get("JAVA_HOME") or "/opt/homebrew/opt/openjdk@17"
        command = ["./gradlew", ":app:testDebugUnitTest"]
        for name in args.tests:
            command += ["--tests", name]
        results_dir = repo_root / "app/build/test-results/testDebugUnitTest"
        if results_dir.is_dir():
            for stale in results_dir.glob("*.xml"):
                stale.unlink()
        env = dict(os.environ, JAVA_HOME=java_home)
        proc = subprocess.run(command, cwd=str(repo_root), capture_output=True, text=True, timeout=args.timeout, env=env)
        log_path = run_dir / "gradle.log"
        log_path.write_text(proc.stdout + "\n" + proc.stderr, encoding="utf-8")
        report.add_output(log_path, "log")
        report.environment["java"] = java_home
        report.environment["gradle"] = "gradlew wrapper"
        report.parameters["tests"] = args.tests

        xml_files = sorted(glob.glob(str(results_dir / "*.xml")))
        if proc.returncode != 0 and not xml_files:
            report.warn("gradle 未产出 JUnit 结果：编译或环境失败")
            report.error((proc.stderr or proc.stdout).strip()[-800:])
            return emit(report, STATUS_NOT_RUN, EXIT_ENV, args)

        total = failures = errors = skipped = 0
        classes = []
        for xml_file in xml_files:
            tree = ET.parse(xml_file)
            suite = tree.getroot()
            total += int(suite.get("tests", 0))
            failures += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
            classes.append(suite.get("name", Path(xml_file).stem))
        summary = {
            "classes": classes,
            "tests": total,
            "failures": failures,
            "errors": errors,
            "skipped": skipped,
            "exit_code": proc.returncode,
        }
        summary_path = run_dir / "junit-summary.json"
        write_json(summary_path, summary)
        report.add_output(summary_path, "junit")
        report.dataset["sample_count"] = total
        report.metrics.update(summary)
        report.check("tests_executed", total > 0, f"{total} tests in {len(classes)} classes")
        report.check("no_failures", failures == 0 and errors == 0 and proc.returncode == 0, f"{failures} failures / {errors} errors")
        if skipped:
            report.warn(f"{skipped} 个用例被跳过")
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

"""回滚演练（T5.2）：切换指针并用 hash 校验资产，不需要重新发版。

演练在临时目录进行，不改动 app/src/main/assets 的实际内容；输出回滚前后 hash 对照。
"""

from __future__ import annotations

import argparse
import shutil
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
    sha256_file,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "T5.2"


def main() -> int:
    parser = build_parser(
        prog="rollback_test.py",
        version=VERSION,
        task_id=TASK_ID,
        description="演练模型版本回滚并核对 hash",
    )
    parser.add_argument("--current", required=True)
    parser.add_argument("--previous", required=True)
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(current=args.current, previous=args.previous)

    def body() -> int:
        current_dir = resolve_path(repo_root, args.current)
        previous_dir = resolve_path(repo_root, args.previous)
        if current_dir is None or not current_dir.is_dir():
            raise CliError(f"当前版本目录不存在：{args.current}")
        if previous_dir is None or not previous_dir.is_dir():
            raise CliError(f"上一版本目录不存在：{args.previous}")
        current_files = {p.name: sha256_file(p) for p in current_dir.iterdir() if p.is_file()}
        previous_files = {p.name: sha256_file(p) for p in previous_dir.iterdir() if p.is_file()}
        if not current_files or not previous_files:
            raise CliError("目录为空，无法演练")

        rehearsal = run_dir / "rehearsal"
        shutil.copytree(current_dir, rehearsal)
        for item in list(rehearsal.iterdir()):
            if item.is_file():
                item.unlink()
        for name in previous_files:
            shutil.copy2(previous_dir / name, rehearsal / name)
        restored = {p.name: sha256_file(p) for p in rehearsal.iterdir() if p.is_file()}

        report.check("previous_restored", restored == previous_files, f"{len(restored)} files")
        report.check("current_differs", current_files != previous_files, "两个版本内容不同才能证明回滚生效")
        report.metrics.update(current=current_files, previous=previous_files, restored=restored)
        write_json(run_dir / "rollback.json", {"current": current_files, "previous": previous_files, "restored": restored})
        report.add_output(run_dir / "rollback.json", "evidence")
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

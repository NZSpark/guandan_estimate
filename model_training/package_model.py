"""模型打包与版本记录（T5.2）。

把模型、manifest、训练/评估引用打成可回滚的版本目录：写入 version.json（数据/模型 hash、
训练命令、seed、依赖版本、评估报告引用），并保留上一版资产。
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
        prog="package_model.py",
        version=VERSION,
        task_id=TASK_ID,
        description="打模型版本包并保留上一版，供回滚演练使用",
    )
    parser.add_argument("--model", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--previous", default="app/src/main/assets/models")
    parser.add_argument("--report", default=None, help="评估报告 JSON（写入引用）")
    parser.add_argument("--training-command", default="")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(model=args.model, manifest=args.manifest, previous=args.previous, training_command=args.training_command)

    def body() -> int:
        model_path = resolve_path(repo_root, args.model)
        manifest_path = resolve_path(repo_root, args.manifest)
        if model_path is None or not model_path.is_file():
            raise CliError(f"模型不存在：{args.model}")
        if manifest_path is None or not manifest_path.is_file():
            raise CliError(f"manifest 不存在：{args.manifest}")
        manifest = read_json(manifest_path)
        report.add_input(model_path, "model")
        report.add_input(manifest_path, "manifest")
        model_sha = sha256_file(model_path)
        recorded_sha = manifest.get("model_sha256") or (manifest.get("model") or {}).get("sha256")
        report.check("manifest_matches_model", recorded_sha in {None, model_sha}, f"manifest={recorded_sha} file={model_sha}")

        package_dir = run_dir / "package"
        package_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(model_path, package_dir / model_path.name)
        shutil.copy2(manifest_path, package_dir / "model_manifest.json")
        previous_dir = resolve_path(repo_root, args.previous)
        backed_up = []
        if previous_dir and previous_dir.is_dir():
            backup = package_dir / "previous"
            backup.mkdir(parents=True, exist_ok=True)
            for item in previous_dir.iterdir():
                if item.is_file():
                    shutil.copy2(item, backup / item.name)
                    backed_up.append({"file": item.name, "sha256": sha256_file(item)})

        evaluation = None
        if args.report:
            report_path = resolve_path(repo_root, args.report)
            if report_path and report_path.is_file():
                evaluation = {"path": args.report, "sha256": sha256_file(report_path)}
                payload = read_json(report_path)
                evaluation["status"] = payload.get("status")
                evaluation["metrics"] = payload.get("metrics")

        version = {
            "schema_version": 1,
            "model_version": manifest.get("version") or model_path.stem,
            "model_sha256": model_sha,
            "model_size": model_path.stat().st_size,
            "dataset_version": manifest.get("dataset_version", ""),
            "training_command": args.training_command,
            "seed": manifest.get("seed"),
            "dependencies": manifest.get("dependencies", {}),
            "evaluation": evaluation,
            "previous": backed_up,
            "rollback_pointer": "previous/",
        }
        write_json(package_dir / "version.json", version)
        report.add_output(package_dir / "version.json", "version")
        report.metrics.update(model_sha256=model_sha, previous_files=len(backed_up), model_size=model_path.stat().st_size)
        report.check("version_recorded", bool(version["model_sha256"]), version["model_version"])
        report.check("rollback_available", bool(backed_up) or previous_dir is None or not previous_dir.exists(), f"{len(backed_up)} files backed up")
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

"""许可与来源登记（X1）。

四层：数据来源、代码依赖、预训练权重、最终模型资产。只登记来源与许可字段，
不因许可阻断训练或发布（2026-11-02 起删除 licenses.json 与 dataset-license 门禁）。
"""

from __future__ import annotations

import argparse
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
    rel,
    resolve_path,
    run,
    sha256_file,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "X1"

CODE_DEPENDENCIES = [
    {"name": "ultralytics", "license": "AGPL-3.0", "use": "train-only", "distributed": False},
    {"name": "tensorflow", "license": "apache-2.0", "use": "train/export", "distributed": False},
    {"name": "torch", "license": "bsd-3-clause", "use": "train", "distributed": False},
    {"name": "pillow", "license": "hpnd", "use": "tooling", "distributed": False},
    {"name": "numpy", "license": "bsd-3-clause", "use": "tooling", "distributed": False},
    {"name": "litert", "license": "apache-2.0", "use": "android-runtime", "distributed": True},
]


def main() -> int:
    parser = build_parser(
        prog="license_audit.py",
        version=VERSION,
        task_id=TASK_ID,
        description="数据/代码/权重/模型四层许可登记",
    )
    parser.add_argument("--sources", default="model_training/datasets/sources.json")
    parser.add_argument("--artifacts", default=None, help="发布产物目录（APK / models）")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(sources=args.sources, artifacts=args.artifacts)

    def body() -> int:
        sources_path = resolve_path(repo_root, args.sources)
        if sources_path is None or not sources_path.is_file():
            raise CliError(f"来源登记不存在：{args.sources}")
        report.add_input(sources_path, "sources")
        sources = read_json(sources_path).get("sources", [])

        licensed, incomplete = [], []
        for source in sources:
            license_name = str(source.get("license", "unknown")).lower()
            missing = [field for field in ("id", "url", "version", "license") if not source.get(field)]
            if missing:
                incomplete.append({"id": source.get("id"), "missing": missing})
                continue
            licensed.append({"id": source.get("id"), "license": license_name})

        report.metrics.update(
            sources=len(sources),
            licensed_sources=[item["id"] for item in licensed],
            incomplete_sources=incomplete,
            code_dependencies=CODE_DEPENDENCIES,
        )
        if incomplete:
            report.warn(f"{len(incomplete)} 个来源缺少许可字段，仅记录不阻断")
        report.check(
            "agpl_weights_not_distributed",
            all(not d["distributed"] for d in CODE_DEPENDENCIES if d["license"].startswith("agpl")),
            "ultralytics 只在训练侧",
        )

        if args.artifacts:
            artifacts_dir = resolve_path(repo_root, args.artifacts)
            if artifacts_dir is None or not artifacts_dir.is_dir():
                report.warn(f"产物目录不存在，跳过产物层登记：{args.artifacts}")
            else:
                artifacts = [p for p in artifacts_dir.rglob("*") if p.is_file()]
                report.check("artifact_listing", bool(artifacts), f"{len(artifacts)} files")
                report.metrics["artifacts"] = [
                    {"path": rel(repo_root, p), "sha256": sha256_file(p), "size": p.stat().st_size}
                    for p in artifacts[:200]
                ]

        status = report.decide_status()
        audit_path = run_dir / "license-audit.json"
        write_json(audit_path, report.to_json(status))
        report.add_output(audit_path, "audit")
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

"""导入人工修正（T5.1 后半段）。

要求每条修正带审核人、审核时间与样本 hash；未审批、缺 hash、或目标是 case3/case4 的修正一律拒绝。
脚本只做校验与合并，不代替人工点“批准”。
"""

from __future__ import annotations

import argparse

from _cli import (
    EXIT_APPROVAL,
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_BLOCKED,
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
TASK_ID = "T5.1"


def main() -> int:
    parser = build_parser(
        prog="import_corrections.py",
        version=VERSION,
        task_id=TASK_ID,
        description="校验并合并人工修正，拒绝未审批与 golden 样��",
    )
    parser.add_argument("--annotations", required=True)
    parser.add_argument("--manifest", default="model_training/datasets/dataset_manifest.json")
    parser.add_argument("--golden-names", default="case4")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(annotations=args.annotations, manifest=args.manifest)

    def body() -> int:
        annotations_path = resolve_path(repo_root, args.annotations)
        manifest_path = resolve_path(repo_root, args.manifest)
        if annotations_path is None or not annotations_path.is_file():
            raise CliError(f"修正文件不存在：{args.annotations}")
        if manifest_path is None or not manifest_path.is_file():
            raise CliError(f"manifest 不存在：{args.manifest}")
        report.add_input(annotations_path, "annotations")
        report.add_input(manifest_path, "manifest")
        payload = read_json(annotations_path)
        corrections = payload.get("corrections") or []
        manifest = read_json(manifest_path)
        golden = {name.strip() for name in args.golden_names.split(",")}
        existing = {entry.get("image") for entry in manifest.get("images", [])}

        rejected, accepted = [], []
        for correction in corrections:
            reason = None
            if any(name in str(correction.get("image", "")) for name in golden):
                reason = "golden 样本不得进入训练"
            elif not correction.get("reviewer") or not correction.get("reviewed_at"):
                reason = "缺少审核人或审核时间"
            elif not correction.get("sha256"):
                reason = "缺少样本 hash"
            elif correction.get("image") not in existing:
                reason = "样本不在 manifest 中"
            if reason:
                rejected.append({"image": correction.get("image"), "reason": reason})
            else:
                accepted.append(correction)

        if accepted:
            merged = dict(manifest)
            index = {entry["image"]: entry for entry in merged.get("images", [])}
            for correction in accepted:
                index[correction["image"]]["cards"] = correction.get("cards", [])
            merged["version"] = f"{manifest.get('version', 'dataset')}+corrections{len(accepted)}"
            merged_path = run_dir / "dataset_manifest.json"
            write_json(merged_path, merged)
            report.add_output(merged_path, "manifest")
            report.add_input(annotations_path, "corrections")

        report.dataset["sample_count"] = len(corrections)
        report.metrics.update(accepted=len(accepted), rejected=rejected)
        report.check("corrections_present", bool(corrections), f"{len(corrections)} corrections")
        report.check("all_accepted", not rejected, f"{len(rejected)} rejected")
        status = report.decide_status()
        if status == STATUS_BLOCKED or (rejected and not accepted):
            return emit(report, STATUS_BLOCKED, EXIT_APPROVAL, args)
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

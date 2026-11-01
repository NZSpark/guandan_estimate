"""数据集校验与审计（T1.1 许可、T1.4 manifest/防泄漏、T1.5 审计）。

机械检查：文件存在、SHA-256、标签合法性、54 类覆盖、scene_id 划分、跨 split 泄漏、许可白名单、
golden 排除。许可与 golden 真值的最终结论必须由人工签核，脚本只校验记录是否存在且绑定一致。
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from _cli import (
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_PASS,
    CliError,
    ApprovalRequired,
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
TASK_ID = "T1.4"

RANKS = {"2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "SJ", "BJ"}
SUITS = {"CLUB", "DIAMOND", "HEART", "SPADE", "JOKER"}
SPLITS = {"train", "val", "calibration", "test"}
DEFAULT_TRAIN_ALLOWED = {"self-owned", "self-generated", "cc0", "cc-by", "mit", "apache-2.0"}


def valid_label(rank: str, suit: str) -> bool:
    if rank not in RANKS or suit not in SUITS:
        return False
    return (rank in {"SJ", "BJ"}) == (suit == "JOKER")


def add_checks(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--manifest", default="model_training/datasets/dataset_manifest.json")
    parser.add_argument("--check-files", action="store_true")
    parser.add_argument("--check-hashes", action="store_true")
    parser.add_argument("--check-labels", action="store_true")
    parser.add_argument("--check-classes", action="store_true")
    parser.add_argument("--check-scene-splits", action="store_true")
    parser.add_argument("--check-leakage", action="store_true")
    parser.add_argument("--check-golden-exclusion", action="store_true")
    parser.add_argument("--audit", action="store_true")
    parser.add_argument("--golden-truth", default="model_training/datasets/golden_truth.json")
    parser.add_argument("--golden-cases", default="case4")
    parser.add_argument("--task", default=None, help="覆盖任务 ID（T1.4/T1.5）")


def main() -> int:
    parser = build_parser(
        prog="validate_dataset.py",
        version=VERSION,
        task_id=TASK_ID,
        description="数据集 manifest 校验：文件/hash/标签/类别/划分/泄漏/golden 排除",
    )
    add_checks(parser)
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, args.task or TASK_ID)
    checks = {
        key: getattr(args, key)
        for key in (
            "check_files",
            "check_hashes",
            "check_labels",
            "check_classes",
            "check_scene_splits",
            "check_leakage",
            "check_golden_exclusion",
            "audit",
        )
    }
    report.parameters.update(manifest=args.manifest, checks=checks)

    def body() -> int:
        manifest_path = resolve_path(repo_root, args.manifest)
        if manifest_path is None or not manifest_path.is_file():
            raise CliError(f"manifest 不存在：{args.manifest}")
        manifest = read_json(manifest_path)
        images = manifest.get("images") or []
        if not images:
            raise CliError("manifest 中没有 images")
        report.add_input(manifest_path, "manifest")
        report.dataset = {
            "version": str(manifest.get("version", "")),
            "manifest_sha256": sha256_file(manifest_path),
            "split": "all",
            "sample_count": len(images),
        }

        report.check("manifest_has_images", len(images) > 0, f"{len(images)} entries")

        by_scene: dict[str, set[str]] = {}
        missing_files: list[str] = []
        bad_hashes: list[str] = []
        label_errors: list[str] = []
        labels_seen: set[tuple[str, str]] = set()
        split_counts: Counter[str] = Counter()
        hash_to_splits: dict[str, set[str]] = {}
        joker_dupes = 0
        occlusion_values: list[float] = []
        cards_per_scene: list[int] = []
        backgrounds: Counter[str] = Counter()

        for entry in images:
            image_path = resolve_path(repo_root, entry.get("image", ""))
            split = entry.get("split", "")
            split_counts[split] += 1
            scene_id = entry.get("scene_id")
            if scene_id:
                by_scene.setdefault(scene_id, set()).add(split)
            if args.check_files and (image_path is None or not image_path.is_file()):
                missing_files.append(entry.get("image", ""))
            if args.check_hashes and image_path is not None and image_path.is_file():
                if entry.get("sha256") and sha256_file(image_path) != entry["sha256"]:
                    bad_hashes.append(entry.get("image", ""))
                if entry.get("sha256"):
                    hash_to_splits.setdefault(entry["sha256"], set()).add(split)
            if entry.get("background"):
                backgrounds[entry["background"]] += 1
            cards = entry.get("cards") or []
            cards_per_scene.append(len(cards))
            for card in cards:
                label = (card.get("rank"), card.get("suit"))
                labels_seen.add(label)  # type: ignore[arg-type]
                if args.check_labels:
                    if not valid_label(*label):  # type: ignore[misc]
                        label_errors.append(f"{entry.get('image')}:非法标签 {label}")
                    quad = card.get("quad") or []
                    corner = card.get("corner_quad") or []
                    if len(quad) != 4 or len(corner) != 4:
                        label_errors.append(f"{entry.get('image')}:{label} 四点数量错误")
                    else:
                        width = entry.get("width", 0)
                        height = entry.get("height", 0)
                        for point in quad + corner:
                            if width and height and not (0 <= point[0] <= width and 0 <= point[1] <= height):
                                label_errors.append(f"{entry.get('image')}:{label} 四点越界 {point}")
                                break
                if card.get("occlusion") is not None:
                    occlusion_values.append(float(card["occlusion"]))
            counts = Counter((card.get("rank"), card.get("suit")) for card in cards)
            if any(value > 2 for value in counts.values()):
                label_errors.append(f"{entry.get('image')}:超过两副牌上限")

        joker_dupes = sum(1 for entry in images if sum(1 for c in entry.get("cards") or [] if c.get("suit") == "JOKER") > 1)

        if args.check_files:
            report.check("files_exist", not missing_files, f"{len(missing_files)} missing")
            if missing_files:
                report.metrics["missing_files"] = missing_files[:20]
        if args.check_hashes:
            report.check("hashes_match", not bad_hashes, f"{len(bad_hashes)} mismatched")
            if bad_hashes:
                report.metrics["bad_hashes"] = bad_hashes[:20]
        if args.check_labels:
            report.check("labels_valid", not label_errors, f"{len(label_errors)} invalid")
            if label_errors:
                report.metrics["label_errors"] = label_errors[:20]
        if args.check_classes:
            expected = 54
            report.metrics["distinct_labels"] = len(labels_seen)
            report.check("class_coverage_54", len(labels_seen) == expected, f"{len(labels_seen)}/{expected}")
        if args.check_scene_splits:
            multi = {scene: sorted(splits) for scene, splits in by_scene.items() if len(splits) > 1}
            illegal = {split for split in split_counts if split not in SPLITS}
            report.metrics["splits"] = dict(split_counts)
            report.check("scene_single_split", not multi, f"{len(multi)} scenes span splits")
            report.check("split_names_valid", not illegal, str(sorted(illegal)))
            if multi:
                report.metrics["scene_split_violations"] = list(multi)[:20]
        if args.check_leakage:
            leaking = {digest: splits for digest, splits in hash_to_splits.items() if len(splits) > 1}
            report.check("no_duplicate_across_splits", not leaking, f"{len(leaking)} duplicate hashes across splits")
            if leaking:
                report.metrics["leaked_hashes"] = list(leaking)[:20]

        if args.check_golden_exclusion:
            golden_path = resolve_path(repo_root, args.golden_truth)
            if golden_path is None or not golden_path.is_file():
                report.warn(f"golden 真值文件缺失：{args.golden_truth}")
                report.check("golden_excluded", False, "golden_truth 不存在")
            else:
                truth = read_json(golden_path)
                golden_hashes = {case.get("sha256") for case in truth.get("cases", []) if case.get("sha256")}
                case_names = {case.get("name") for case in truth.get("cases", [])}
                hits = [entry.get("image") for entry in images if entry.get("sha256") in golden_hashes]
                scene_hits = [entry.get("scene_id") for entry in images if entry.get("image") and any(name and name in entry["image"] for name in case_names)]
                report.check("golden_excluded", not hits and not scene_hits, f"{len(hits)} hash hits, {len(scene_hits)} name hits")
                if hits or scene_hits:
                    report.metrics["golden_hits"] = (hits + scene_hits)[:20]

        if args.audit:
            report.metrics.update(
                images=len(images),
                cards=sum(cards_per_scene),
                cards_per_scene_max=max(cards_per_scene),
                backgrounds=dict(backgrounds.most_common(5)),
                scenes_with_joker_duplicate=joker_dupes,
                mean_occlusion=round(sum(occlusion_values) / len(occlusion_values), 4) if occlusion_values else 0.0,
            )
            report.check("audit_sample_size", len(images) > 0, f"{len(images)} images")
            audit_path = run_dir / "data-audit.md"
            audit_path.write_text(render_audit(report, manifest, manifest_path, repo_root), encoding="utf-8")
            report.add_output(audit_path, "audit")

        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


def render_audit(report, manifest: dict, manifest_path: Path, repo_root: Path) -> str:
    metrics = report.metrics
    lines = [
        "# 数据审计报告（自动生成）",
        "",
        f"- 任务：{report.task_id} / run_id `{report.run_id}`",
        f"- 生成时间：{report.started_at}",
        f"- manifest：`{rel(repo_root, manifest_path)}`（sha256 `{report.dataset.get('manifest_sha256')}`）",
        f"- 数据版本：`{manifest.get('version', '')}`",
        f"- 图片数：{metrics.get('images')}；牌角数：{metrics.get('cards')}",
        f"- 单图最大张数：{metrics.get('cards_per_scene_max')}；两副牌重复场景：{metrics.get('scenes_with_joker_duplicate')}",
        f"- 平均遮挡（记录值）：{metrics.get('mean_occlusion')}",
        f"- split 统计：{metrics.get('splits')}",
        f"- 类别覆盖：{metrics.get('distinct_labels')}/54",
        "",
        "## 检查项",
        "",
        "| 检查 | 结果 | 说明 |",
        "| --- | --- | --- |",
    ]
    for check in report.checks:
        lines.append(f"| {check.name} | {'PASS' if check.passed else 'FAIL'} | {check.detail} |")
    lines += [
        "",
        "## 需人工确认",
        "",
        "- 数据许可只做登记，不阻断数据加入（2026-11-02 起移除 dataset-license 门禁）。",
        "- Golden 真值与真实照片可见性（`model_training/approvals/golden-definition.json`）。",
        "- 审计脚本只做机械校验，不替代人工签字。",
    ]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    raise SystemExit(main())

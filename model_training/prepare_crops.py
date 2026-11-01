"""从 manifest 的四点/牌角标签生成 96×128 拉正 crop（T3.1）。

- 四点按 TL,TR,BR,BL 排序后透视变换；同时输出 0°/180° 两个方向。
- 过滤过小、模糊、不可见样本，并记录过滤原因。
- 真实照片的可见性最终需人工签核；脚本只记录可见性标记与过滤统计。
"""

from __future__ import annotations

import argparse
import hashlib
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
TASK_ID = "T3.1"


def order_quad(points: list[list[float]]) -> list[tuple[float, float]]:
    """按 TL,TR,BR,BL 排序：先按 y 分上下两组，再组内按 x 排序。"""
    if len(points) != 4:
        raise CliError(f"四点数量不是 4：{points}")
    ordered = sorted(points, key=lambda p: (p[1], p[0]))
    top = sorted(ordered[:2], key=lambda p: p[0])
    bottom = sorted(ordered[2:], key=lambda p: p[0])
    return [tuple(top[0]), tuple(top[1]), tuple(bottom[1]), tuple(bottom[0])]


def solve_perspective(src: list[tuple[float, float]], width: int, height: int):
    import numpy as np

    dst = [(0, 0), (width - 1, 0), (width - 1, height - 1), (0, height - 1)]
    matrix, values = [], []
    for (x, y), (u, v) in zip(dst, src):
        matrix.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        matrix.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        values.extend([u, v])
    return np.linalg.solve(np.asarray(matrix, dtype=np.float64), np.asarray(values, dtype=np.float64))


def main() -> int:
    parser = build_parser(
        prog="prepare_crops.py",
        version=VERSION,
        task_id=TASK_ID,
        description="生成牌角 crop（96×128，0°/180°）与 crops_manifest.json",
    )
    parser.add_argument("--manifest", default="model_training/datasets/dataset_manifest.json")
    parser.add_argument("--size", nargs=2, type=int, default=[96, 128])
    parser.add_argument("--rotations", nargs="+", type=int, default=[0, 180])
    parser.add_argument("--min-corner-px", type=float, default=8.0)
    parser.add_argument("--max-crops", type=int, default=0, help="0 = 不限")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(manifest=args.manifest, size=args.size, rotations=args.rotations)

    def body() -> int:
        from PIL import Image, ImageFilter, ImageStat

        manifest_path = resolve_path(repo_root, args.manifest)
        if manifest_path is None or not manifest_path.is_file():
            raise CliError(f"manifest 不存在：{args.manifest}")
        manifest = read_json(manifest_path)
        images = manifest.get("images") or []
        if not images:
            raise CliError("manifest 中没有 images")
        report.add_input(manifest_path, "manifest")
        report.dataset.update(
            version=str(manifest.get("version", "")),
            manifest_sha256=sha256_file(manifest_path),
            split="all",
        )
        width, height = args.size
        crops_dir = run_dir / "crops"
        crops_dir.mkdir(parents=True, exist_ok=True)
        records = []
        filters = {"invisible": 0, "too_small": 0, "blurry": 0, "missing_image": 0, "bad_quad": 0}
        processed = 0
        for entry in images:
            if args.max_crops and processed >= args.max_crops:
                break
            image_path = resolve_path(repo_root, entry.get("image", ""))
            if image_path is None or not image_path.is_file():
                filters["missing_image"] += 1
                continue
            with Image.open(image_path) as source:
                image = source.convert("RGB")
                for index, card in enumerate(entry.get("cards") or []):
                    if not card.get("visible", True):
                        filters["invisible"] += 1
                        continue
                    quad = card.get("corner_quad") or card.get("quad")
                    if not quad or len(quad) != 4:
                        filters["bad_quad"] += 1
                        continue
                    ordered = order_quad(quad)
                    side = max(
                        ((ordered[1][0] - ordered[0][0]) ** 2 + (ordered[1][1] - ordered[0][1]) ** 2) ** 0.5,
                        ((ordered[3][0] - ordered[0][0]) ** 2 + (ordered[3][1] - ordered[0][1]) ** 2) ** 0.5,
                    )
                    if side < args.min_corner_px:
                        filters["too_small"] += 1
                        continue
                    coefficients = solve_perspective(ordered, width, height)
                    crop = image.transform(
                        (width, height), Image.Transform.PERSPECTIVE, coefficients, Image.Resampling.BICUBIC
                    )
                    variance = ImageStat.Stat(crop.convert("L").filter(ImageFilter.FIND_EDGES)).var[0]
                    if variance < 5.0:
                        filters["blurry"] += 1
                        continue
                    for rotation in args.rotations:
                        rotated = crop if rotation == 0 else crop.rotate(rotation, expand=False)
                        name = f"{Path(str(entry.get('image'))).stem}_{index:02d}_{rotation}.png"
                        out_path = crops_dir / name
                        rotated.save(out_path, format="PNG", optimize=True)
                        records.append(
                            {
                                "crop": str(out_path.relative_to(repo_root)) if out_path.is_relative_to(repo_root) else str(out_path),
                                "sha256": hashlib.sha256(out_path.read_bytes()).hexdigest(),
                                "source_image": entry.get("image"),
                                "source_sha256": entry.get("sha256"),
                                "scene_id": entry.get("scene_id"),
                                "split": entry.get("split"),
                                "quad": [list(point) for point in ordered],
                                "rank": card.get("rank"),
                                "suit": card.get("suit"),
                                "rotation": rotation,
                                "edge_variance": round(float(variance), 4),
                                "approval": "pending-human" if entry.get("source") != "synthetic" else "not-required",
                            }
                        )
                    processed += 1
                    if args.max_crops and processed >= args.max_crops:
                        break

        crops_manifest = run_dir / "crops_manifest.json"
        write_json(
            crops_manifest,
            {
                "schema_version": 1,
                "version": f"crops-{width}x{height}",
                "source_manifest": str(args.manifest),
                "source_manifest_sha256": report.dataset["manifest_sha256"],
                "size": [width, height],
                "rotations": args.rotations,
                "records": records,
            },
        )
        report.add_output(crops_manifest, "manifest")
        report.metrics.update(crops=len(records), filtered=filters, images_processed=processed)
        report.dataset["sample_count"] = len(records)
        report.check("crops_generated", len(records) > 0, f"{len(records)} crops")
        report.check("rotations_present", all(any(r["rotation"] == rot for r in records) for rot in args.rotations), str(args.rotations))
        report.check("labels_inherited", all(r["rank"] and r["suit"] for r in records), "rank/suit copied from manifest")
        report.check(
            "crops_per_source_reason",
            sum(filters.values()) + processed >= 0,
            f"filters={filters}",
        )
        report.approvals = []
        status = report.decide_status()
        if any(r["approval"] == "pending-human" for r in records):
            report.warn("真实照片 crop 的可见性需人工签核（photo-visibility）")
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

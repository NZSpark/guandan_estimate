"""把公开四点/框标注转换成项目统一的 OBB + manifest（T1.2）。

两种输入格式：

- `sidecar`（默认）：`<input>/<image>` 与同名 `<image>.json`（四点或 points）；
- `hf-json`：Hugging Face 分割 JSON（`img_path` + `card_points` + `class_label`），
  配合 `--classes`（`名称 id` 行）与 `--label-map`（名称 → rank/suit）。

`hf-json` 只处理 `--annotations` 里列出的文件，并要求对应图片已由 T1.1 下载到 `--input`。
真实照片（`photo: true`）需要 photo-visibility 签核，未签核为 BLOCKED；机器渲染的公开图不需要。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from _cli import (
    EXIT_APPROVAL,
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_BLOCKED,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    load_approvals,
    load_config,
    make_report,
    prepare,
    rel,
    resolve_path,
    run,
    sha256_file,
    write_json,
)

VERSION = "1.1.0"
TASK_ID = "T1.2"

CORNER_FRACTION = 0.35


def order_quad(points):
    ordered = sorted(points, key=lambda p: (p[1], p[0]))
    top = sorted(ordered[:2], key=lambda p: p[0])
    bottom = sorted(ordered[2:], key=lambda p: p[0])
    return [list(top[0]), list(top[1]), list(bottom[1]), list(bottom[0])]


def corner_of(quad, fraction: float):
    xs = [p[0] for p in quad]
    ys = [p[1] for p in quad]
    width = max(xs) - min(xs)
    height = max(ys) - min(ys)
    return [
        [min(xs), min(ys)],
        [min(xs) + width * fraction, min(ys)],
        [min(xs) + width * fraction, min(ys) + height * fraction],
        [min(xs), min(ys) + height * fraction],
    ]


def load_classes(path: Path | None) -> dict[int, str]:
    names: dict[int, str] = {}
    if path is None or not path.is_file():
        return names
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            names[int(parts[1])] = parts[0]
    return names


def main() -> int:
    parser = build_parser(
        prog="convert_public_annotations.py",
        version=VERSION,
        task_id=TASK_ID,
        description="公开四点标注 → 项目 OBB / 牌角标注 + 统一 manifest",
    )
    parser.add_argument("--input", required=True, help="原始标注目录（hf-json 模式下为数据集根目录）")
    parser.add_argument("--label-map", default=None, help="类别映射 YAML（含 label_map 段）")
    parser.add_argument("--format", choices=["sidecar", "hf-json"], default="sidecar")
    parser.add_argument("--annotations", default=None, help="hf-json：逗号分隔的标注文件（相对 --input）")
    parser.add_argument("--classes", default=None, help="hf-json：classes.txt（相对 --input）")
    parser.add_argument("--image-root", default=None, help="hf-json：图片根目录；默认等于 --input")
    parser.add_argument("--max-images", type=int, default=0, help="hf-json：最多转换多少张（0 表示不限）")
    parser.add_argument("--source-id", default="hf-playing-cards", help="写入 manifest 的来源标记")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(
        input=args.input,
        label_map=args.label_map,
        format=args.format,
        annotations=args.annotations,
        classes=args.classes,
        max_images=args.max_images,
    )

    def record_for(image: Path, scene_id: str, cards: list, license_name: str, photo: bool) -> dict:
        return {
            "image": rel(repo_root, image),
            "sha256": sha256_file(image),
            "scene_id": scene_id,
            "split": "train",
            "source": args.source_id,
            "license": license_name,
            "photo": photo,
            "cards": cards,
        }

    def body() -> int:
        input_dir = resolve_path(repo_root, args.input)
        if input_dir is None or not input_dir.is_dir():
            raise CliError(f"输入目录不存在：{args.input}")
        label_map = load_config(repo_root, args.label_map) if args.label_map else {}
        label_map = label_map.get("label_map", label_map)
        fraction = CORNER_FRACTION
        entries, invalid = [], []
        skipped_no_image: list[dict] = []
        considered = 0

        if args.format == "hf-json":
            if not args.annotations:
                raise CliError("--format hf-json 需要 --annotations")
            image_root = resolve_path(repo_root, args.image_root) if args.image_root else input_dir
            classes_path = None
            if args.classes:
                # classes 与 annotations 一样相对 --input 解析，避免默认到仓库根后静默变空。
                classes_path = resolve_path(repo_root, f"{args.input.rstrip('/')}/{args.classes}") or resolve_path(repo_root, args.classes)
            classes = load_classes(classes_path)
            report.metrics["class_names"] = len(classes)
            if args.classes and not classes:
                raise CliError(f"classes 文件无法解析：{args.classes}")
            for name in [p.strip() for p in args.annotations.split(",") if p.strip()]:
                annotation_path = resolve_path(repo_root, f"{args.input.rstrip('/')}/{name}")
                if annotation_path is None or not annotation_path.is_file():
                    raise CliError(f"标注文件不存在：{name}")
                report.add_input(annotation_path, "annotations")
                payload = json.loads(annotation_path.read_text(encoding="utf-8"))
                keys = sorted(payload.keys(), key=lambda k: int(k) if str(k).isdigit() else 0)
                for key in keys:
                    if args.max_images and considered >= args.max_images:
                        break
                    sample = payload[key]
                    considered += 1
                    image = image_root / sample["img_path"]
                    if not image.is_file():
                        # 标注存在但图片未下载（T1.1 的 --limit-images 限制），属于未取样，不是非法标签。
                        skipped_no_image.append({"image": sample.get("img_path"), "reason": "图片未下载"})
                        continue
                    considered += 1
                    cards = []
                    for index, item in enumerate(sample.get("card_points") or []):
                        if not isinstance(item, (list, tuple)) or len(item) != 2:
                            invalid.append({"image": sample.get("img_path"), "index": index, "reason": "card_points 结构不符"})
                            continue
                        points, class_id = item
                        if not points or len(points) != 4:
                            invalid.append({"image": sample.get("img_path"), "index": index, "reason": "四点缺失"})
                            continue
                        mapped = label_map.get(classes.get(int(class_id), ""))
                        if not mapped:
                            invalid.append({"image": sample.get("img_path"), "index": index, "reason": f"类别未映射：class_id={class_id}"})
                            continue
                        quad = order_quad([[float(p[0]), float(p[1])] for p in points])
                        cards.append(
                            {
                                "rank": mapped["rank"],
                                "suit": mapped["suit"],
                                "quad": quad,
                                "corner_quad": corner_of(quad, fraction),
                                "visible": True,
                                "class_id": int(class_id),
                            }
                        )
                    if cards:
                        scene = f"{args.source_id}-{Path(sample['img_path']).stem}"
                        entries.append(record_for(image, scene, cards, "mit", False))
        else:
            images = sorted([p for p in input_dir.iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"}])
            if not images:
                raise CliError(f"{args.input} 下没有图片；T1.2 需要先通过 T1.1 准备数据")
            considered = len(images)
            for image in images:
                sidecar = image.with_suffix(image.suffix + ".json")
                if not sidecar.is_file():
                    sidecar = image.with_suffix(".json")
                if not sidecar.is_file():
                    invalid.append({"image": image.name, "reason": "缺少标注文件"})
                    continue
                payload = json.loads(sidecar.read_text(encoding="utf-8"))
                cards = []
                for index, card in enumerate(payload.get("cards") or payload.get("shapes") or []):
                    points = card.get("quad") or card.get("points")
                    if not points or len(points) != 4:
                        invalid.append({"image": image.name, "index": index, "reason": "四点缺失"})
                        continue
                    quad = order_quad([[float(p[0]), float(p[1])] for p in points])
                    label = card.get("label") or card.get("name") or ""
                    mapped = label_map.get(label, {})
                    rank = card.get("rank") or mapped.get("rank")
                    suit = card.get("suit") or mapped.get("suit")
                    if not rank or not suit:
                        invalid.append({"image": image.name, "index": index, "reason": f"类别未映射：{label}"})
                        continue
                    cards.append(
                        {
                            "rank": rank,
                            "suit": suit,
                            "quad": quad,
                            "corner_quad": corner_of(quad, fraction),
                            "visible": bool(card.get("visible", True)),
                        }
                    )
                if cards:
                    entries.append(
                        record_for(
                            image,
                            payload.get("scene_id", image.stem),
                            cards,
                            payload.get("license", "unknown"),
                            bool(payload.get("photo", False)),
                        )
                    )

        manifest = {
            "schema_version": 1,
            "dataset": "public-converted",
            "version": f"public-{args.source_id}-{len(entries)}",
            "images": entries,
            "invalid": invalid,
        }
        manifest_path = run_dir / "dataset_manifest.json"
        write_json(manifest_path, manifest)
        report.add_output(manifest_path, "manifest")
        report.dataset = {
            "version": manifest["version"],
            "manifest_sha256": sha256_file(manifest_path),
            "split": "all",
            "sample_count": len(entries),
        }
        map_path = run_dir / "label_map.json"
        write_json(map_path, label_map)
        report.add_output(map_path, "label-map")

        card_count = sum(len(e["cards"]) for e in entries)
        combos = {(c["rank"], c["suit"]) for e in entries for c in e["cards"]}
        report.metrics.update(
            images_considered=considered,
            converted=len(entries),
            cards=card_count,
            distinct_combos=len(combos),
            skipped_no_image=len(skipped_no_image),
            invalid=invalid[:20],
            invalid_count=len(invalid),
            corner_fraction=fraction,
        )
        report.check("images_converted", len(entries) > 0, f"{len(entries)}/{considered}")
        report.check("no_invalid_labels", not invalid, f"{len(invalid)} invalid")
        if skipped_no_image:
            report.warn(f"{len(skipped_no_image)} 条标注因图片未下载而跳过（T1.1 --limit-images 限制）")
        report.check(
            "class_combinations_valid",
            all(0 < len(r) and (s in {"CLUB", "DIAMOND", "HEART", "SPADE", "JOKER"}) for r, s in combos)
            and all((r in {"SJ", "BJ"}) == (s == "JOKER") for r, s in combos),
            f"{len(combos)} 种组合",
        )

        photo_entries = [e for e in entries if e.get("photo")]
        # 只有真的含真实照片时才要求可见性签核；纯机器渲染的公开图不应被判 BLOCKED。
        if photo_entries:
            report.approvals = load_approvals(repo_root, ["photo-visibility"])
        status = report.decide_status()
        if photo_entries and not any(a["valid"] for a in report.approvals):
            report.warn(f"{len(photo_entries)} 张真实照片需要人工可见性确认（photo-visibility 签核）")
            if status == STATUS_PASS:
                return emit(report, STATUS_BLOCKED, EXIT_APPROVAL, args)
        if not photo_entries and entries:
            report.warn("本批全部为机器渲染的公开图，不涉及真实照片可见性签核")
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

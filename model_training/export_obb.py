"""把四点标注导成单类 `card_corner` 的 YOLO-OBB 数据集。

两种输入：

- `--annotations annotations.json --images testcases`：人工确认过的真实照片标注（旧流程）。
- `--manifest dataset_manifest.json`：合成/公开 manifest，直接使用每张牌的 `corner_quad`（推荐）。

输出 `images/{train,val}`、`labels/{train,val}` 与 `data.yaml`，供 `train_detector.py --data` 使用。
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
from pathlib import Path

from PIL import Image


def quad_of(item: dict) -> list[list[float]]:
    if "quad" in item:
        return item["quad"]
    x, y, w, h = item["bbox"]
    return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]


def normalize(points: list[list[float]], width: int, height: int) -> list[str]:
    values: list[str] = []
    for x, y in points:
        values += [f"{max(0.0, min(1.0, x / width)):.8f}", f"{max(0.0, min(1.0, y / height)):.8f}"]
    return values


def export_from_annotations(args, images_root: Path, output: Path) -> tuple[int, int]:
    data = json.loads(args.annotations.read_text(encoding="utf-8"))["images"]
    pending = sum(1 for items in data.values() for item in items if item.get("needs_review"))
    if pending:
        raise SystemExit(f"{pending} automatic annotations still need manual confirmation in annotate.py")
    names = sorted(name for name, items in data.items() if items)
    if len(names) < 2:
        raise SystemExit("Annotate at least two photos before exporting a detector dataset")
    random.Random(args.seed).shuffle(names)
    validation_count = max(1, round(len(names) * args.validation_fraction))
    validation = set(names[:validation_count])
    written = 0
    for name in names:
        source = images_root / name
        split = "val" if name in validation else "train"
        shutil.copy2(source, output / "images" / split / source.name)
        with Image.open(source) as image:
            width, height = image.size
        lines = []
        for item in data[name]:
            points = quad_of(item)
            if len(points) != 4:
                raise ValueError(f"{name} has a non-quadrilateral annotation")
            lines.append("0 " + " ".join(normalize(points, width, height)))
        (output / "labels" / split / f"{source.stem}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        written += 1
    return written - len(validation), len(validation)


def export_from_manifest(args, repo_root: Path, output: Path) -> tuple[int, int]:
    payload = json.loads(args.manifest.read_text(encoding="utf-8"))
    entries = payload.get("images") or []
    if not entries:
        raise SystemExit(f"{args.manifest} 没有 images")
    counts = {"train": 0, "val": 0}
    kept_cards = filtered = 0
    for entry in entries:
        split = entry.get("split")
        if split not in counts:
            continue
        if args.limit_scenes and sum(counts.values()) >= args.limit_scenes:
            break
        image_path = Path(entry["image"])
        if not image_path.is_absolute():
            image_path = repo_root / image_path
        if not image_path.is_file():
            filtered += 1
            continue
        lines = []
        for card in entry.get("cards") or []:
            if args.min_corner_px and not card.get("visible", True):
                filtered += 1
                continue
            points = card.get("corner_quad") or card.get("quad")
            if not points or len(points) != 4:
                filtered += 1
                continue
            width, height = entry.get("width"), entry.get("height")
            if not width or not height:
                with Image.open(image_path) as image:
                    width, height = image.size
            lines.append("0 " + " ".join(normalize(points, width, height)))
        shutil.copy2(image_path, output / "images" / split / image_path.name)
        (output / "labels" / split / f"{image_path.stem}.txt").write_text(
            "\n".join(lines) + ("\n" if lines else ""), encoding="utf-8"
        )
        counts[split] += 1
        kept_cards += len(lines)
    print(
        f"[obb] train={counts['train']} scenes, val={counts['val']} scenes, "
        f"{kept_cards} corner labels, filtered={filtered}"
    )
    return counts["train"], counts["val"]


def main() -> None:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--annotations", type=Path, default=here / "annotations.json")
    parser.add_argument("--images", type=Path, default=here.parent / "testcases")
    parser.add_argument("--manifest", type=Path, default=None, help="dataset_manifest.json（合成/公开数据）")
    parser.add_argument("--repo-root", type=Path, default=here.parent)
    parser.add_argument("--output", type=Path, default=here / "output" / "obb_dataset")
    parser.add_argument("--validation-fraction", type=float, default=0.2)
    parser.add_argument("--limit-scenes", type=int, default=0, help="0 = 不限（按 manifest 顺序）")
    parser.add_argument("--min-corner-px", action="store_true", help="跳过 visible=false 的牌")
    parser.add_argument("--seed", type=int, default=20260810)
    args = parser.parse_args()

    output = args.output
    if output.exists():
        shutil.rmtree(output)
    for split in ("train", "val"):
        (output / "images" / split).mkdir(parents=True)
        (output / "labels" / split).mkdir(parents=True)

    if args.manifest:
        train_count, val_count = export_from_manifest(args, args.repo_root.resolve(), output)
    else:
        train_count, val_count = export_from_annotations(args, args.images, output)

    yaml = f"path: {output.resolve().as_posix()}\ntrain: images/train\nval: images/val\nnames:\n  0: card_corner\n"
    (output / "data.yaml").write_text(yaml, encoding="utf-8")
    print(f"Exported {train_count} training and {val_count} validation scenes to {output}")


if __name__ == "__main__":
    main()

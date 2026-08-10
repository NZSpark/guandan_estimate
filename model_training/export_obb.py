"""Export quadrilateral annotations as a one-class YOLO oriented-bounding-box dataset."""

from __future__ import annotations

import argparse
import json
import random
import shutil
from pathlib import Path

from PIL import Image


def quad(item: dict) -> list[list[float]]:
    if "quad" in item:
        return item["quad"]
    x, y, w, h = item["bbox"]
    return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]


def main() -> None:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--annotations", type=Path, default=here / "annotations.json")
    parser.add_argument("--images", type=Path, default=here.parent / "testcases")
    parser.add_argument("--output", type=Path, default=here / "output" / "obb_dataset")
    parser.add_argument("--validation-fraction", type=float, default=.2)
    parser.add_argument("--seed", type=int, default=20260810)
    args = parser.parse_args()

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

    if args.output.exists():
        shutil.rmtree(args.output)
    for split in ("train", "val"):
        (args.output / "images" / split).mkdir(parents=True)
        (args.output / "labels" / split).mkdir(parents=True)

    for name in names:
        source = args.images / name
        split = "val" if name in validation else "train"
        destination = args.output / "images" / split / source.name
        shutil.copy2(source, destination)
        with Image.open(source) as image:
            width, height = image.size
        lines = []
        for item in data[name]:
            points = quad(item)
            if len(points) != 4:
                raise ValueError(f"{name} has a non-quadrilateral annotation")
            normalized = []
            for x, y in points:
                normalized += [max(0.0, min(1.0, x / width)), max(0.0, min(1.0, y / height))]
            lines.append("0 " + " ".join(f"{value:.8f}" for value in normalized))
        (args.output / "labels" / split / f"{source.stem}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    yaml = f"path: {args.output.resolve().as_posix()}\ntrain: images/train\nval: images/val\nnames:\n  0: card_corner\n"
    (args.output / "data.yaml").write_text(yaml, encoding="utf-8")
    print(f"Exported {len(names) - len(validation)} training and {len(validation)} validation photos to {args.output}")


if __name__ == "__main__":
    main()

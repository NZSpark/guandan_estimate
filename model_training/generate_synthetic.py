"""合成两副牌手牌场景，生成图像 + 四点/牌角标签 + manifest（T1.3）。

- 从仓库自有牌面素材取 54 张牌面，允许同一 (rank,suit) 在一局出现两次（两副牌）。
- 每局 1–27 张，随机行数、间距、重叠 0–80%、缩放、旋转、亮度/模糊/JPEG 噪声。
- 标签由渲染参数直接生成：整牌四点 quad、左上 35% 牌角四点 corner_quad、rank/suit/visible。
- 另有负样本：空桌、只有牌背、半张牌。
- 固定 seed 完全可复现；split 按 scene_id 稳定分配。

限制：场景使用旋转 + 缩放，不含透视变形（PIL 透视会让四点标签与像素产生系统性偏差）。
"""

from __future__ import annotations

import hashlib
import math
import random
from pathlib import Path

from _cli import (
    EXIT_PASS,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    make_report,
    prepare,
    run,
    write_json,
    write_jsonl,
)

VERSION = "1.0.0"
TASK_ID = "T1.3"

BASE_W, BASE_H = 140, 196
CORNER_FRACTION = 0.35

SUIT_FILES = {"Club": "CLUB", "Diamond": "DIAMOND", "Heart": "HEART", "Spade": "SPADE"}
RANK_FILES = ["2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A"]
JOKER_FILES = {"JOKER-A.png": ("BJ", "JOKER"), "JOKER-B.png": ("SJ", "JOKER")}


def card_label_map(cards_dir: Path) -> dict[tuple[str, str], Path]:
    """把 card_png 目录映射成 (rank, suit) -> 文件。"""
    mapping: dict[tuple[str, str], Path] = {}
    for name, label in JOKER_FILES.items():
        path = cards_dir / name
        if path.is_file():
            mapping[label] = path
    for suit_name, suit in SUIT_FILES.items():
        for rank in RANK_FILES:
            path = cards_dir / f"{suit_name}{rank}.png"
            if path.is_file():
                mapping[(rank, suit)] = path
    return mapping


def _rotation_sign() -> int:
    """用同一套 PIL 调用标定“旋转公式符号”，避免四点标签与像素错位。"""
    from PIL import Image, ImageDraw

    probe = Image.new("L", (21, 29), 0)
    ImageDraw.Draw(probe).rectangle([0, 0, 6, 6], fill=255)
    angle = 30
    rotated = probe.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC)
    pixels = rotated.load()
    xs = ys = count = 0
    for y in range(rotated.height):
        for x in range(rotated.width):
            if pixels[x, y] > 128:
                xs += x
                ys += y
                count += 1
    if count == 0:
        return 1
    measured = (xs / count, ys / count)
    best_sign, best_error = 1, float("inf")
    for sign in (1, -1):
        point = rotate_point((3, 3), (21, 29), angle * sign)
        error = (point[0] - measured[0]) ** 2 + (point[1] - measured[1]) ** 2
        if error < best_error:
            best_sign, best_error = sign, error
    return best_sign


def rotate_point(point: tuple[float, float], size: tuple[int, int], angle: float, sign: int = 1):
    """把卡片坐标点绕中心旋转，并映射到 expand=True 的输出坐标。"""
    width, height = size
    radians = math.radians(angle * sign)
    cos, sin = math.cos(radians), math.sin(radians)
    dx, dy = point[0] - width / 2, point[1] - height / 2
    rotated_w = abs(width * cos) + abs(height * sin)
    rotated_h = abs(width * sin) + abs(height * cos)
    return (rotated_w / 2 + dx * cos + dy * sin, rotated_h / 2 - dx * sin + dy * cos)


def bbox_iou(a: list[list[float]], b: list[list[float]]) -> float:
    ax1, ay1 = min(p[0] for p in a), min(p[1] for p in a)
    ax2, ay2 = max(p[0] for p in a), max(p[1] for p in a)
    bx1, by1 = min(p[0] for p in b), min(p[1] for p in b)
    bx2, by2 = max(p[0] for p in b), max(p[1] for p in b)
    inter_w = max(0.0, min(ax2, bx2) - max(ax1, bx1))
    inter_h = max(0.0, min(ay2, by2) - max(ay1, by1))
    inter = inter_w * inter_h
    area_a = max(1e-6, (ax2 - ax1) * (ay2 - ay1))
    return inter / area_a


def apply_perspective(image, rng, strength: float):
    """对整张场景做一次轻微透视变换，返回新图与 3×3 homography。

    PIL 的 perspective 会与标签错位，所以统一用 cv2 算矩阵、再对四个角点做同一变换。
    """
    import cv2
    import numpy as np
    from PIL import Image

    width, height = image.size
    jitter = strength * 0.08 * min(width, height)
    source = np.float32([[0, 0], [width, 0], [width, height], [0, height]])
    target = source + np.float32(
        [[rng.uniform(-jitter, jitter), rng.uniform(-jitter, jitter)] for _ in range(4)]
    )
    matrix = cv2.getPerspectiveTransform(source, target)
    warped = cv2.warpPerspective(
        np.asarray(image)[:, :, ::-1].copy(), matrix, (width, height), borderValue=(245, 245, 245)
    )
    return Image.fromarray(warped[:, :, ::-1]), matrix


def map_perspective(points, matrix, width: int, height: int):
    """把四点过同一 homography，并裁到画布内（透视会把角点推出边界）。"""
    import cv2
    import numpy as np

    array = np.asarray(points, dtype=np.float32).reshape(-1, 1, 2)
    return [
        [round(min(max(float(point[0]), 0.0), float(width - 1)), 2),
         round(min(max(float(point[1]), 0.0), float(height - 1)), 2)]
        for point in cv2.perspectiveTransform(array, matrix).reshape(-1, 2)
    ]


def assign_split(scene_id: str) -> str:
    bucket = int(hashlib.sha256(scene_id.encode("utf-8")).hexdigest()[:8], 16) % 100
    if bucket < 70:
        return "train"
    if bucket < 85:
        return "val"
    if bucket < 90:
        return "calibration"
    return "test"


def main() -> int:
    parser = build_parser(
        prog="generate_synthetic.py",
        version=VERSION,
        task_id=TASK_ID,
        description="生成合成手牌场景（两副牌、1–27 张、负样本）与四点/牌角标签",
        need_seed=True,
    )
    parser.add_argument("--cards", default="card_picture/card_png", help="牌面素材目录")
    parser.add_argument("--backgrounds", default="model_training/datasets/backgrounds", help="背景目录")
    parser.add_argument("--count", type=int, default=2000, help="场景数量（文档默认 20000，按磁盘与本机耗时调整）")
    parser.add_argument("--width", type=int, default=1024)
    parser.add_argument("--height", type=int, default=768)
    parser.add_argument("--jpeg-probability", type=float, default=0.25)
    parser.add_argument("--angle-range", type=float, default=20.0, help="牌面旋转幅度（±度）；真机训练建议 180")
    parser.add_argument("--perspective-probability", type=float, default=0.0, help="场景级透视畸变概率")
    parser.add_argument("--perspective-strength", type=float, default=1.0, help="透视畸变强度（角点抖动比例）")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(
        cards=args.cards,
        backgrounds=args.backgrounds,
        count=args.count,
        width=args.width,
        height=args.height,
        jpeg_probability=args.jpeg_probability,
        angle_range=args.angle_range,
        perspective_probability=args.perspective_probability,
        seed=args.seed,
    )

    def body() -> int:
        from PIL import Image, ImageEnhance, ImageFilter

        if args.count <= 0:
            raise CliError("--count 必须大于 0")
        cards_dir = repo_root / args.cards
        backgrounds_dir = repo_root / args.backgrounds
        labels = card_label_map(cards_dir)
        if len(labels) != 54:
            raise CliError(f"牌面素材不完整：期望 54 张，实际 {len(labels)} 张（{cards_dir}）")
        background_files = sorted(backgrounds_dir.glob("*.png")) if backgrounds_dir.is_dir() else []
        if not background_files:
            raise CliError(f"缺少背景素材：{backgrounds_dir}；先运行 generate_backgrounds.py")

        faces = {label: Image.open(path).convert("RGBA") for label, path in labels.items()}
        back_path = cards_dir / "Background.png"
        if not back_path.is_file():
            raise CliError(f"缺少牌背素材：{back_path}")
        back = Image.open(back_path).convert("RGBA")
        backgrounds = [Image.open(path).convert("RGB") for path in background_files]

        sign = _rotation_sign()
        report.check("quad_rotation_calibrated", sign in (1, -1), f"rotation sign={sign}")

        images_dir = run_dir / "images"
        images_dir.mkdir(parents=True, exist_ok=True)
        rng = random.Random(args.seed)
        entries: list[dict] = []
        rows: list[dict] = []

        for index in range(args.count):
            scene_id = f"syn-{args.seed}-{index:06d}"
            background = backgrounds[index % len(backgrounds)].resize((args.width, args.height))
            kind = "hand"
            draw = rng.random()
            if draw < 0.02:
                kind = "empty"
            elif draw < 0.04:
                kind = "backs"

            canvas = background.copy()
            placed: list[dict] = []
            if kind == "hand":
                total = rng.randint(1, 27)
                row_count = rng.choice([1, 1, 2, 2, 3]) if total > 3 else 1
                row_count = min(row_count, total)
                remaining = total
                for row_index in range(row_count):
                    rows_left = row_count - row_index
                    count = remaining if rows_left == 1 else max(1, remaining // rows_left)
                    remaining -= count
                    placed.extend(
                        _layout_row(
                            rng,
                            count,
                            row_index,
                            row_count,
                            args.width,
                            args.height,
                            len(placed),
                            args.angle_range,
                        )
                    )
                for item, label in zip(placed, _pick_labels(rng, sorted(labels), len(placed))):
                    item["label"] = label
            elif kind == "backs":
                for position in range(rng.randint(2, 5)):
                    placed.append(
                        {
                            "label": None,
                            "angle": rng.uniform(-args.angle_range, args.angle_range),
                            "scale": rng.uniform(0.8, 1.3),
                            "x": rng.uniform(0.1, 0.6) * args.width,
                            "y": rng.uniform(0.1, 0.6) * args.height,
                            "order": position,
                        }
                    )

            for item in placed:
                if item["label"] is None:
                    sprite = back.copy()
                else:
                    sprite = faces[item["label"]].copy()
                target_w = max(24, int(BASE_W * item["scale"]))
                target_h = max(34, int(BASE_H * item["scale"]))
                radians = math.radians(item["angle"])
                cos, sin = abs(math.cos(radians)), abs(math.sin(radians))
                rotated_w = target_w * cos + target_h * sin
                rotated_h = target_w * sin + target_h * cos
                limit = min(args.width * 0.92 / rotated_w, args.height * 0.92 / rotated_h)
                if limit < 1.0:
                    target_w = max(24, int(target_w * limit))
                    target_h = max(34, int(target_h * limit))
                    item["scale"] *= limit
                    rotated_w *= limit
                    rotated_h *= limit
                sprite = sprite.resize((target_w, target_h), Image.Resampling.LANCZOS)
                rotated = sprite.rotate(item["angle"], expand=True, resample=Image.Resampling.BICUBIC)
                item["x"] = min(max(0.0, item["x"]), max(0.0, args.width - rotated.width))
                item["y"] = min(max(0.0, item["y"]), max(0.0, args.height - rotated.height))
                canvas.paste(rotated, (int(item["x"]), int(item["y"])), rotated)
                if item["label"] is not None:
                    quad = [
                        rotate_point(point, (target_w, target_h), item["angle"], sign)
                        for point in [(0, 0), (target_w, 0), (target_w, target_h), (0, target_h)]
                    ]
                    corner = [
                        rotate_point(point, (target_w, target_h), item["angle"], sign)
                        for point in [
                            (0, 0),
                            (target_w * CORNER_FRACTION, 0),
                            (target_w * CORNER_FRACTION, target_h * CORNER_FRACTION),
                            (0, target_h * CORNER_FRACTION),
                        ]
                    ]
                    item["quad"] = [[round(x + item["x"], 2), round(y + item["y"], 2)] for x, y in quad]
                    item["corner_quad"] = [
                        [round(x + item["x"], 2), round(y + item["y"], 2)] for x, y in corner
                    ]

            cards = []
            drawn = [item for item in placed if item["label"] is not None and "corner_quad" in item]
            for position, item in enumerate(drawn):
                covered = 0.0
                for later in drawn[position + 1 :]:
                    covered = max(covered, bbox_iou(item["corner_quad"], later["corner_quad"]))
                cards.append(
                    {
                        "rank": item["label"][0],
                        "suit": item["label"][1],
                        "quad": item["quad"],
                        "corner_quad": item["corner_quad"],
                        "visible": covered < 0.6,
                        "occlusion": round(covered, 4),
                        "rotation": round(item["angle"], 3),
                        "scale": round(item["scale"], 4),
                    }
                )

            if args.perspective_probability > 0 and rng.random() < args.perspective_probability:
                canvas, homography = apply_perspective(canvas, rng, args.perspective_strength)
                for card in cards:
                    card["quad"] = map_perspective(card["quad"], homography, args.width, args.height)
                    card["corner_quad"] = map_perspective(
                        card["corner_quad"], homography, args.width, args.height
                    )

            if kind == "hand" and rng.random() < args.jpeg_probability:
                canvas = canvas.filter(ImageFilter.GaussianBlur(rng.uniform(0.0, 0.8)))
            canvas = ImageEnhance.Brightness(canvas).enhance(rng.uniform(0.85, 1.15))
            canvas = ImageEnhance.Contrast(canvas).enhance(rng.uniform(0.85, 1.15))
            import numpy as np

            noise = Image.effect_noise((args.width, args.height), rng.uniform(2, 12)).convert("L")
            pixels = np.asarray(canvas, dtype=np.int16) + np.asarray(noise, dtype=np.int16)[..., None] - 128
            canvas = Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8))

            file_name = f"{scene_id}.png"
            image_path = images_dir / file_name
            canvas.save(image_path, format="PNG", optimize=True)
            digest = hashlib.sha256(image_path.read_bytes()).hexdigest()
            split = assign_split(scene_id)
            entries.append(
                {
                    "image": str(image_path.relative_to(repo_root)) if image_path.is_relative_to(repo_root) else str(image_path),
                    "sha256": digest,
                    "scene_id": scene_id,
                    "split": split,
                    "source": "synthetic",
                    "source_id": "project-card-png",
                    "license": "self-owned",
                    "width": args.width,
                    "height": args.height,
                    "kind": kind,
                    "background": backgrounds_dir.name + "/" + background_files[index % len(background_files)].name,
                    "background_license": "self-generated",
                    "seed": args.seed,
                    "cards": cards,
                }
            )
            rows.append({"scene_id": scene_id, "kind": kind, "cards": len(cards), "split": split})

        manifest = {
            "schema_version": 1,
            "dataset": "synthetic-hands",
            "version": f"synthetic-seed{args.seed}-n{args.count}",
            "generated_at": report.started_at,
            "generator": "model_training/generate_synthetic.py",
            "parameters": {
                "seed": args.seed,
                "count": args.count,
                "width": args.width,
                "height": args.height,
                "corner_fraction": CORNER_FRACTION,
                "jpeg_probability": args.jpeg_probability,
                "angle_range": args.angle_range,
                "perspective": f"homography p={args.perspective_probability} strength={args.perspective_strength}",
            },
            "images": entries,
        }
        manifest_path = run_dir / "dataset_manifest.json"
        write_json(manifest_path, manifest)
        write_jsonl(run_dir / "scenes.jsonl", rows)

        all_labels = {(card["rank"], card["suit"]) for entry in entries for card in entry["cards"]}
        visible = [c for e in entries for c in e["cards"] if c["visible"]]
        duplicates = 0
        for entry in entries:
            seen: dict[tuple[str, str], int] = {}
            for card in entry["cards"]:
                key = (card["rank"], card["suit"])
                seen[key] = seen.get(key, 0) + 1
            duplicates += sum(1 for count in seen.values() if count > 1)
        splits = {name: sum(1 for e in entries if e["split"] == name) for name in ("train", "val", "calibration", "test")}
        kinds = {name: sum(1 for e in entries if e["kind"] == name) for name in ("hand", "empty", "backs")}

        report.dataset = {
            "version": manifest["version"],
            "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
            "split": "all",
            "sample_count": len(entries),
        }
        report.metrics.update(
            scenes=len(entries),
            cards=sum(len(e["cards"]) for e in entries),
            visible_cards=len(visible),
            distinct_labels=len(all_labels),
            duplicate_scenes=duplicates,
            splits=splits,
            kinds=kinds,
        )
        report.check("sample_scenes", len(entries) == args.count, f"{len(entries)}/{args.count}")
        report.check(
            "full_54_class_coverage",
            len(all_labels) == 54,
            f"{len(all_labels)}/54 labels: {sorted(set(c[0] for c in all_labels))}",
        )
        report.check("joker_both_kinds", {c["rank"] for c in visible if c["suit"] == "JOKER"} >= {"SJ", "BJ"}, "SJ/BJ present")
        report.check("two_deck_duplicates", duplicates > 0, f"{duplicates} scenes repeat a (rank,suit)")
        report.check("negative_samples", kinds["empty"] > 0 and kinds["backs"] > 0, str(kinds))
        report.check("all_splits_populated", all(count > 0 for count in splits.values()), str(splits))
        report.check("labels_within_canvas", all(0 <= p[0] <= args.width and 0 <= p[1] <= args.height for e in entries for c in e["cards"] for p in c["quad"]), "quads inside canvas")
        report.check("seed_recorded", args.seed is not None, f"seed={args.seed}")
        report.add_output(manifest_path, "manifest")
        report.add_output(run_dir / "scenes.jsonl", "log")
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else 1, args)

    return run(body, args, report)


def _layout_row(
    rng: random.Random,
    count: int,
    row_index: int,
    row_count: int,
    width: int,
    height: int,
    start_order: int,
    angle_range: float = 20.0,
) -> list[dict]:
    """一行扇形牌：随机重叠 0–80%，整体倾斜度由 angle_range 控制。"""
    scale = rng.uniform(0.55, 1.05)
    card_w = BASE_W * scale
    card_h = BASE_H * scale
    overlap = rng.uniform(0.0, 0.8)
    step = max(8.0, card_w * (1.0 - overlap))
    row_width = step * (count - 1) + card_w
    if row_width > width * 0.98:
        squeeze = width * 0.98 / row_width
        step *= squeeze
        row_width = step * (count - 1) + card_w
    x0 = max(0.0, (width - row_width) / 2.0) + rng.uniform(-width * 0.03, width * 0.03)
    y0 = (height / (row_count + 1)) * (row_index + 1) - card_h / 2 + rng.uniform(-height * 0.04, height * 0.04)
    y0 = min(max(0.0, y0), height - card_h)
    tilt = rng.uniform(-angle_range, angle_range)
    items = []
    for position in range(count):
        items.append(
            {
                "label": None,
                "angle": tilt + rng.uniform(-8.0, 8.0),
                "scale": scale,
                "x": x0 + position * step,
                "y": y0 + rng.uniform(-height * 0.02, height * 0.02),
                "order": start_order + position,
            }
        )
    return items


def _pick_labels(rng: random.Random, labels: list[tuple[str, str]], count: int) -> list[tuple[str, str]]:
    """两副牌：每个 (rank,suit) 最多出现两次。"""
    pool: list[tuple[str, str]] = []
    for label in labels:
        pool.extend([label, label])
    rng.shuffle(pool)
    return pool[:count]


if __name__ == "__main__":
    raise SystemExit(main())

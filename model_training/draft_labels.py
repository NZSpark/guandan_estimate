"""用现有检测器 + 分类器，给无人工真值的图片生成"待核对草稿"（T0.5 辅助工具）。

这不是验收脚本：输出的每一条都带 `needs_review: true`，**绝不写入
`golden_truth.json` 或 `annotations.json`**。人工核对后由人把确认结果登记进
`golden_truth.json` 并签核 `approvals/golden-definition.json`。

流程与训练/评估链路保持一致：

1. `card_corner` OBB 检测器出牌角四点；
2. `order_quad` + `solve_perspective`（复用 `prepare_crops.py`）按 TL,TR,BR,BL 拉正；
3. 分类器（.keras 或 .tflite）跑 0°/180° TTA，取联合概率更高者；
4. 用四边形 IoU 标出疑似重复检测，供人工判断是重复框还是真的成对同名牌。

默认参数按实测选：检测器在训练尺寸 640 上对真机照片的召回明显高于 1280/1920
（case17：640→11 框，1280→3 框，1920→0 框），宽图像素尺寸只带来分布漂移。

除 JSON/Markdown 外还会为每张图生成叠加图（检测框 + 预测标签），便于人工逐张核对。

用法（仓库根目录）：

    model_training/.venv/bin/python model_training/draft_labels.py \
        --detector output/runs/20261101/T2.2/card_corner_obb_train/weights/best.pt \
        --classifier output/runs/20261101/T3.4/export/classifier.tflite \
        --output-dir output/runs/20261101/T0.5-draft
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from labels import RANKS, SUITS  # noqa: E402
from predict import apply_temperature, polygon_iou  # noqa: E402
from prepare_crops import order_quad, solve_perspective  # noqa: E402

# 与 App PassPlanner 对齐（CardRecognizer.kt / RecognitionWorker.kt）。
APP_ROTATIONS = (-60.0, -40.0, -20.0, 20.0, 40.0, 60.0, 90.0, 180.0)
APP_CROP_FRACTION = 0.55
APP_GRID_STEP = 0.225


def load_classifier(path: Path):
    """返回 (infer, size)，infer 输入 NHWC float32 [0,1]。"""

    import numpy as np

    if path.suffix == ".tflite":
        import tensorflow as tf

        interpreter = tf.lite.Interpreter(model_path=str(path))
        interpreter.allocate_tensors()
        input_detail = interpreter.get_input_details()[0]
        outputs = interpreter.get_output_details()
        shape = [int(v) for v in input_detail["shape"]]

        def infer(pixels):
            interpreter.set_tensor(input_detail["index"], pixels[None, ...].astype(np.float32))
            interpreter.invoke()
            tensors = [interpreter.get_tensor(detail["index"])[0] for detail in outputs]
            rank = next(t for t in tensors if t.shape[0] == len(RANKS))
            suit = next(t for t in tensors if t.shape[0] == len(SUITS))
            return rank, suit

        engine = "tflite"
    else:
        import tensorflow as tf

        model = tf.keras.models.load_model(str(path))

        def infer(pixels):
            output = model(pixels[None, ...], training=False)
            return output["rank"][0].numpy(), output["suit"][0].numpy()

        # Keras 输入的第一维是动态 batch（None），不能直接 int()
        shape = [1 if v is None else int(v) for v in model.inputs[0].shape]
        engine = "keras"

    height, width = shape[1], shape[2]
    return infer, (width, height), engine


def warp(image, quad, size):
    from PIL import Image

    width, height = size
    ordered = order_quad(quad)
    coefficients = solve_perspective(ordered, width, height)
    return image.transform(
        (width, height), Image.Transform.PERSPECTIVE, coefficients, Image.Resampling.BICUBIC
    )


def classify(infer, model, temperature, min_margin, quad_iou_duplicate):
    """对一张图所有检测框分类，返回按行优先排序的条目。"""

    import numpy as np
    from PIL import Image

    rows = []
    for index, (quad, _score, image) in enumerate(model):
        best = None
        for rotation in (0, 180):
            sample = image if rotation == 0 else image.rotate(180, expand=False)
            pixels = np.asarray(sample.convert("RGB"), dtype=np.float32) / 255.0
            rank_raw, suit_raw = infer(pixels)
            rank_prob = apply_temperature([float(v) for v in rank_raw], temperature)
            suit_prob = apply_temperature([float(v) for v in suit_raw], temperature)
            rank_index = max(range(len(rank_prob)), key=rank_prob.__getitem__)
            suit_index = max(range(len(suit_prob)), key=suit_prob.__getitem__)
            confidence = rank_prob[rank_index] * suit_prob[suit_index]
            rank_sorted = sorted(rank_prob, reverse=True)
            suit_sorted = sorted(suit_prob, reverse=True)
            margin = min(rank_sorted[0] - rank_sorted[1], suit_sorted[0] - suit_sorted[1])
            candidate = {
                "rotation": rotation,
                "rank": RANKS[rank_index],
                "suit": SUITS[suit_index],
                "confidence": confidence,
                "margin": margin,
            }
            if best is None or candidate["confidence"] > best["confidence"]:
                best = candidate
        rows.append(
            {
                "index": index,
                "quad": [[round(float(p[0]), 1), round(float(p[1]), 1)] for p in quad],
                "detection_score": round(float(_score), 4),
                "rank": best["rank"],
                "suit": best["suit"],
                "confidence": round(best["confidence"], 4),
                "margin": round(best["margin"], 4),
                "rotation_used": best["rotation"],
                "low_margin": best["margin"] < min_margin,
                "needs_review": True,
            }
        )

    # 疑似重复框：IoU 超过阈值的两条互指，供人工判断而不是自动删除。
    for i, row in enumerate(rows):
        for j in range(i + 1, len(rows)):
            value = polygon_iou(row["quad"], rows[j]["quad"])
            if value >= quad_iou_duplicate:
                row.setdefault("duplicate_of", []).append(rows[j]["index"])
                rows[j].setdefault("duplicate_of", []).append(row["index"])
    for row in rows:
        if "duplicate_of" in row:
            row["needs_review"] = True
    rows.sort(key=lambda r: (min(p[1] for p in r["quad"]), min(p[0] for p in r["quad"])))
    return rows


def card_metrics(case_rows: dict[str, list[dict]], truth: dict[str, "Counter"], det_min: float,
                 cls_min: float, margin_min: float) -> dict:
    """卡片级多重集匹配；与 evaluate.py 的 per-card 口径一致。"""
    from collections import Counter

    tp = fp = fn = 0
    exact_images = 0
    per_case = {}
    for name, rows in case_rows.items():
        expected = truth.get(name)
        if expected is None:
            continue
        kept = [
            row for row in rows
            if row["detection_score"] >= det_min
            and row["confidence"] >= cls_min
            and row["margin"] >= margin_min
        ]
        predicted = Counter((row["rank"], row["suit"]) for row in kept)
        hit = sum((predicted & expected).values())
        tp += hit
        fp += len(kept) - hit
        fn += sum(expected.values()) - hit
        exact_images += int(predicted == expected)
        per_case[name] = {"detections": len(kept), "hits": hit, "truth": sum(expected.values())}
    recall = tp / (tp + fn) if tp + fn else 0.0
    precision = tp / (tp + fp) if tp + fp else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "detector_conf": det_min,
        "classifier_confidence": cls_min,
        "classifier_margin": margin_min,
        "detections": tp + fp,
        "hits": tp,
        "truth": tp + fn,
        "recall": round(recall, 4),
        "precision": round(precision, 4),
        "f1": round(f1, 4),
        "exact_images": exact_images,
        "images": len(per_case),
        "per_case": per_case,
    }


def tile_regions(width: int, height: int, tiles: int, overlap: float) -> list[tuple[int, int, int, int]]:
    """把大图切成 tiles×tiles 的块，每块多带 overlap 比例的重叠。"""
    step = 1.0 / tiles
    span = step * (1.0 + overlap)
    regions = []
    for row in range(tiles):
        for column in range(tiles):
            x = int(width * column * step)
            y = int(height * row * step)
            regions.append((x, y, min(width - x, int(width * span)), min(height - y, int(height * span))))
    return regions


def to_bgr(image):
    import numpy as np

    return np.asarray(image)[:, :, ::-1].copy()


def apply_clahe(image):
    """低光/逆光时先做一次局部对比度均衡，再进模型。"""
    import cv2

    bgr = to_bgr(image)
    lab = cv2.cvtColor(bgr, cv2.COLOR_BGR2LAB)
    lightness, a, b = cv2.split(lab)
    lightness = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(lightness)
    return cv2.cvtColor(cv2.merge((lightness, a, b)), cv2.COLOR_LAB2BGR)


def rotate_image(image, angle: float):
    """旋转并扩容画布（不裁掉角落），返回 PIL 图与「旋转坐标→原图坐标」的逆变换。"""
    import cv2
    import numpy as np
    from PIL import Image

    bgr = np.asarray(image)[:, :, ::-1].copy()
    height, width = bgr.shape[:2]
    matrix = cv2.getRotationMatrix2D((width / 2, height / 2), angle, 1.0)
    cos, sin = abs(matrix[0, 0]), abs(matrix[0, 1])
    new_width, new_height = int(height * sin + width * cos), int(height * cos + width * sin)
    matrix[0, 2] += new_width / 2 - width / 2
    matrix[1, 2] += new_height / 2 - height / 2
    rotated = cv2.warpAffine(bgr, matrix, (new_width, new_height), borderValue=(255, 255, 255))
    return Image.fromarray(rotated[:, :, ::-1]), cv2.invertAffineTransform(matrix)


def map_back(points, inverse):
    import cv2
    import numpy as np

    array = np.asarray(points, dtype=np.float32).reshape(-1, 1, 2)
    return [[float(p[0]), float(p[1])] for p in cv2.transform(array, inverse).reshape(-1, 2)]


def build_passes(size: tuple[int, int], args) -> list[tuple[float, tuple[int, int, int, int], float]]:
    """生成检测 pass：`app` 完全照搬 PassPlanner 的 18 个 pass。

    App 的顺序是：原图 → 8 个整图旋转 → 3×3 个 55% 切片（小图放大 2.4×，大图 1.8×）。
    切片都取自原图，不带旋转。
    """
    width, height = size
    if args.pass_plan == "app":
        passes: list[tuple[float, tuple[int, int, int, int], float]] = [(0.0, (0, 0, width, height), 1.0)]
        for angle in APP_ROTATIONS:
            passes.append((angle, (0, 0, width, height), 1.0))
        crop_width = max(1, int(width * APP_CROP_FRACTION))
        crop_height = max(1, int(height * APP_CROP_FRACTION))
        zoom = 2.4 if min(width, height) < 900 else 1.8
        for row in range(3):
            for column in range(3):
                left = min(int(width * column * APP_GRID_STEP), width - crop_width)
                top = min(int(height * row * APP_GRID_STEP), height - crop_height)
                passes.append((0.0, (left, top, crop_width, crop_height), zoom))
        return passes[:18]

    passes = []
    for angle in (args.rotations or [0.0]):
        regions = (
            [(0, 0, width, height)]
            if args.tiles <= 1
            else tile_regions(width, height, args.tiles, args.tile_overlap)
        )
        for x, y, w, h in regions:
            passes.append((float(angle), (x, y, w, h), 1.0))
    return passes


def detect_quads(detector, image, args) -> list[tuple[list[list[float]], float]]:
    """按 pass 配方检测，所有结果映射回原图坐标后统一 NMS。

    真机照片里的牌角方向远超合成训练的 ±20°：case4 在 0° 一个框都没有，
    转到 60°/90° 才出 14/17 个框，所以固定单角度推理会把整张图判成“没有牌”。
    `--pass-plan app` 进一步补齐 App 的局部放大切片，用于离线对齐真机配方。
    """
    from PIL import Image

    collected: list[tuple[list[list[float]], float]] = []
    for angle, (x, y, width, height), zoom in build_passes(image.size, args):
        rotated, inverse = rotate_image(image, angle) if angle else (image, None)
        crop = rotated.crop((x, y, x + width, y + height))
        if zoom != 1.0:
            crop = crop.resize(
                (max(1, round(crop.width * zoom)), max(1, round(crop.height * zoom))),
                Image.Resampling.BICUBIC,
            )
        source = apply_clahe(crop) if args.clahe else to_bgr(crop)
        result = detector.predict(
            source=source, imgsz=args.image_size, conf=args.conf, iou=args.nms_iou, verbose=False
        )[0]
        obb = getattr(result, "obb", None)
        if obb is None or len(obb) == 0:
            continue
        quads = obb.xyxyxyxy.tolist() if hasattr(obb.xyxyxyxy, "tolist") else list(obb.xyxyxyxy)
        scores = obb.conf.tolist() if hasattr(obb.conf, "tolist") else list(obb.conf)
        for quad, score in zip(quads, scores):
            points = [[float(point[0]) / zoom + x, float(point[1]) / zoom + y] for point in quad]
            if inverse is not None:
                points = map_back(points, inverse)
            collected.append((points, float(score)))
    kept: list[tuple[list[list[float]], float]] = []
    for quad, score in sorted(collected, key=lambda item: -item[1]):
        if any(polygon_iou(quad, previous[0]) >= args.nms_iou for previous in kept):
            continue
        kept.append((quad, score))
    return kept


def draw_overlay(path: Path, rows: list[dict], out_path: Path, max_side: int = 1400) -> None:
    """把检测框与预测标签画回原图，供人工核对位置。"""

    from PIL import Image, ImageDraw, ImageFont

    with Image.open(path) as source:
        image = source.convert("RGB")
    scale = min(1.0, max_side / max(image.size))
    if scale < 1:
        image = image.resize(
            (max(1, round(image.width * scale)), max(1, round(image.height * scale))),
            Image.Resampling.LANCZOS,
        )
    draw = ImageDraw.Draw(image)
    try:
        font = ImageFont.load_default(size=max(12, round(16 * scale * 2)))
    except TypeError:  # Pillow < 10 不支持 size 参数
        font = ImageFont.load_default()
    for row in rows:
        points = [(row["quad"][i][0] * scale, row["quad"][i][1] * scale) for i in range(4)]
        colour = (0, 220, 0) if row["confidence"] >= 0.5 else (255, 165, 0)
        draw.polygon(points, outline=colour)
        label = f"#{row['index']} {row['suit']} {row['rank']} {row['confidence']:.2f}"
        left = min(p[0] for p in points)
        top = min(p[1] for p in points)
        draw.text((left, max(0, top - 18)), label, fill=colour, font=font)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    image.save(out_path, format="JPEG", quality=88)


def main() -> int:
    parser = argparse.ArgumentParser(description="生成无真值图片的牌面草稿（不写真值）")
    parser.add_argument("--detector", required=True)
    parser.add_argument("--classifier", required=True)
    parser.add_argument("--golden", default="model_training/datasets/golden_truth.json")
    parser.add_argument("--only-unlabeled", action="store_true", default=True)
    parser.add_argument("--cases", nargs="*", default=None, help="只跑指定 case（默认跑全部无真值 case）")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--overlay-dir", default=None, help="叠加图输出目录，默认 <output-dir>/overlays")
    parser.add_argument("--image-size", type=int, default=640, help="与训练尺寸一致；调大反而掉召回")
    parser.add_argument("--conf", type=float, default=0.05, help="草稿要召回优先")
    parser.add_argument("--nms-iou", type=float, default=0.5)
    parser.add_argument("--temperature", type=float, default=2.0)
    parser.add_argument("--min-margin", type=float, default=0.02)
    parser.add_argument("--duplicate-iou", type=float, default=0.45)
    parser.add_argument(
        "--rotations",
        nargs="*",
        type=float,
        default=[0.0],
        help="旋转 TTA 角度；真机照片建议 0 30 60 90（合成训练只覆盖 ±20°）",
    )
    parser.add_argument("--tiles", type=int, default=1, help="切片数（1=整图；大图用 2 或 3 保留原生分辨率）")
    parser.add_argument("--tile-overlap", type=float, default=0.2, help="切片重叠比例")
    parser.add_argument(
        "--pass-plan",
        choices=["simple", "app"],
        default="simple",
        help="app=照搬 App 的 18 个 pass（原图+8 旋转+3×3 放大切片）",
    )
    parser.add_argument("--clahe", action="store_true", help="先做一次局部对比度均衡（低光照片）")
    parser.add_argument(
        "--evaluate",
        action="store_true",
        help="按 golden 真值计算卡片级指标（需要 case 带 cards）",
    )
    parser.add_argument(
        "--conf-sweep",
        default="0.2,0.25,0.3,0.35,0.4,0.5,0.6",
        help="检测阈值扫描（逗号分隔）；检测本身只跑一次最低阈值",
    )
    parser.add_argument(
        "--gate-sweep",
        default="0,0;0.5,0.02;0.6,0.05;0.7,0.1",
        help="分类器门控扫描，格式 cls_conf,margin;cls_conf,margin",
    )
    args = parser.parse_args()

    from PIL import Image
    from ultralytics import YOLO

    golden = json.loads(Path(args.golden).read_text(encoding="utf-8"))
    cases = [c for c in golden["cases"] if not c.get("cards")]
    if args.cases:
        wanted = set(args.cases)
        cases = [c for c in golden["cases"] if c["name"] in wanted]
    if not cases:
        print("没有需要生成草稿的 case", file=sys.stderr)
        return 1

    infer, size, engine = load_classifier(Path(args.classifier))
    detector = YOLO(args.detector)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    from collections import Counter

    truth = {
        case["name"]: Counter((card["rank"], card["suit"]) for card in case.get("cards") or [])
        for case in golden["cases"]
        if case.get("cards")
    }
    case_rows: dict[str, list[dict]] = {}
    sweep: list[dict] = []

    draft = {
        "kind": "draft-not-truth",
        "needs_review": True,
        "generated_by": "draft_labels.py",
        "detector": args.detector,
        "classifier": args.classifier,
        "classifier_engine": engine,
        "image_size": args.image_size,
        "conf": args.conf,
        "pass_plan": args.pass_plan,
        "temperature": args.temperature,
        "tiles": args.tiles,
        "tile_overlap": args.tile_overlap,
        "rotations": args.rotations,
        "clahe": args.clahe,
        "cases": {},
    }
    lines = [
        "# 牌面草稿（未核对，不是真值）",
        "",
        f"- 检测器：`{args.detector}`（imgsz={args.image_size}, conf={args.conf}, plan={args.pass_plan}, "
        f"tiles={args.tiles}, rotations={args.rotations}, clahe={args.clahe}）",
        f"- 分类器：`{args.classifier}`（{engine}, TTA 0°/180°, T={args.temperature}）",
        "- 每一行都必须人工核对；`?` 表示低 margin 或疑似重复框。",
        "",
    ]
    for case in cases:
        path = Path(case["path"])
        if not path.is_file():
            print(f"跳过缺失图片：{path}", file=sys.stderr)
            continue
        with Image.open(path) as source:
            image = source.convert("RGB")
        detections = [
            (quad, score, warp(image, quad, size)) for quad, score in detect_quads(detector, image, args)
        ]
        rows = classify(infer, detections, args.temperature, args.min_margin, args.duplicate_iou)
        case_rows[case["name"]] = rows
        overlay_dir = Path(args.overlay_dir) if args.overlay_dir else output_dir / "overlays"
        overlay_path = overlay_dir / f"{case['name']}-draft.jpg"
        draw_overlay(path, rows, overlay_path)
        draft["cases"][case["name"]] = {
            "overlay": str(overlay_path),
            "path": case["path"],
            "image_size": list(Image.open(path).size),
            "detections": len(rows),
            "cards": [{"rank": r["rank"], "suit": r["suit"]} for r in rows],
            "items": rows,
        }
        lines.append(f"## {case['name']}（{case['path']}，检测 {len(rows)} 张）")
        lines.append("")
        lines.append(f"叠加图：`{overlay_path}`")
        lines.append("")
        if not rows:
            lines.append("_检测器没有输出任何牌角，需要人工补。_")
            lines.append("")
            continue
        lines.append("| # | 牌 | 置信度 | margin | 检测分 | 备注 |")
        lines.append("| --- | --- | --- | --- | --- | --- |")
        for row in rows:
            notes = []
            if row["low_margin"]:
                notes.append("低margin")
            if "duplicate_of" in row:
                notes.append("疑似重复#%s" % ",".join(str(v) for v in row["duplicate_of"]))
            lines.append(
                f"| {row['index']} | {row['suit']} {row['rank']} | {row['confidence']:.3f} | "
                f"{row['margin']:.3f} | {row['detection_score']:.3f} | {' '.join(notes)} |"
            )
        lines.append("")

    if args.evaluate:
        confs = [float(v) for v in args.conf_sweep.split(",") if v.strip()]
        gates = []
        for pair in args.gate_sweep.split(";"):
            if not pair.strip():
                continue
            cls_conf, margin = pair.split(",")
            gates.append((float(cls_conf), float(margin)))
        for det_conf in confs:
            for cls_conf, margin in gates:
                sweep.append(card_metrics(case_rows, truth, det_conf, cls_conf, margin))
        draft["truth_matched"] = sorted(truth)
        draft["sweep"] = sweep
        best = max(sweep, key=lambda item: (item["f1"], item["recall"])) if sweep else None
        print(f"{'det':>5} {'cls':>5} {'margin':>6} {'det#':>5} {'hit':>4} {'recall':>7} {'prec':>6} {'f1':>5}")
        for item in sweep:
            marker = " *" if item is best else ""
            print(
                f"{item['detector_conf']:5.2f} {item['classifier_confidence']:5.2f} "
                f"{item['classifier_margin']:6.2f} {item['detections']:5d} {item['hits']:4d} "
                f"{item['recall']:7.3f} {item['precision']:6.3f} {item['f1']:5.3f}{marker}"
            )
        if best:
            print(f"best: {json.dumps({k: best[k] for k in ('detector_conf', 'classifier_confidence', 'classifier_margin', 'recall', 'precision', 'f1')}, ensure_ascii=False)}")
        (output_dir / "sweep.json").write_text(
            json.dumps({"sweep": sweep, "best": best}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    (output_dir / "draft_labels.json").write_text(
        json.dumps(draft, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output_dir / "draft_labels.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    total = sum(c["detections"] for c in draft["cases"].values())
    print(f"cases={len(draft['cases'])} detections={total} -> {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

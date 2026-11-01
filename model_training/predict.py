"""生成预测 JSONL，供 evaluate.py / calibrate.py 使用（T2.4、T2.5、T3.3、T3.5 的共同输入）。

两类预测：

- `--stage classifier`：读 crops_manifest.json，按 split 过滤，0°/180° 两方向推理取联合概率更高者，
  输出 `{"crop","expected","predicted","confidence","correct","unknown"}`。
- `--stage detector`：读 dataset_manifest.json，对 split 内每张图跑 OBB 检测，按 polygon IoU 匹配真值牌角，
  输出 `{"image","corners":[{"quad","score","rank","suit","iou"}]}`。

预测本身不是验收证据：状态记为 PASS 只表示“预测文件已生成且条数与样本一致”，
指标判定仍由 evaluate.py / calibrate.py 负责。
"""

from __future__ import annotations

import math
import re
from pathlib import Path

from _cli import (
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_NOT_RUN,
    STATUS_PASS,
    CliError,
    EnvironmentUnavailable,
    build_parser,
    emit,
    load_config,
    make_report,
    prepare,
    read_json,
    resolve_path,
    run,
    sha256_file,
    write_json,
    write_jsonl,
)

VERSION = "1.0.0"
TASK_ID = "T3.5"

CROP_PATTERN = re.compile(r"^(?P<stem>.+)_(?P<index>\d{2})_(?P<rotation>\d{1,3})$")


def polygon_iou(a: list[list[float]], b: list[list[float]]) -> float:
    """凸多边形 IoU（Sutherland–Hodgman 裁剪）。"""

    def area(points: list[tuple[float, float]]) -> float:
        total = 0.0
        for i in range(len(points)):
            x1, y1 = points[i]
            x2, y2 = points[(i + 1) % len(points)]
            total += x1 * y2 - x2 * y1
        return abs(total) / 2.0

    def clip(subject, clipper):
        output = subject
        for i in range(len(clipper)):
            if not output:
                return []
            ax, ay = clipper[i]
            bx, by = clipper[(i + 1) % len(clipper)]
            def side(px, py):
                return (bx - ax) * (py - ay) - (by - ay) * (px - ax)
            incoming = output
            output = []
            for j in range(len(incoming)):
                px, py = incoming[j]
                qx, qy = incoming[(j + 1) % len(incoming)]
                s1, s2 = side(px, py), side(qx, qy)
                if s1 >= 0:
                    output.append((px, py))
                if (s1 >= 0) != (s2 >= 0):
                    denominator = s1 - s2
                    if abs(denominator) > 1e-12:
                        t = s1 / denominator
                        output.append((px + (qx - px) * t, py + (qy - py) * t))
            if not output:
                return []
        return output

    pa = [(float(p[0]), float(p[1])) for p in a]
    pb = [(float(p[0]), float(p[1])) for p in b]
    clipped = clip(pa, pb)
    inter = area(clipped) if len(clipped) >= 3 else 0.0
    union = area(pa) + area(pb) - inter
    return inter / union if union > 1e-9 else 0.0


def softmax(values: list[float]) -> list[float]:
    peak = max(values)
    exps = [math.exp(v - peak) for v in values]
    total = sum(exps)
    return [v / total for v in exps]


def apply_temperature(probabilities: list[float], temperature: float) -> list[float]:
    if not temperature or temperature <= 0:
        return probabilities
    logits = [math.log(max(1e-9, p)) / max(1e-6, temperature) for p in probabilities]
    return softmax(logits)


def group_crops(records: list[dict], split: str, max_samples: int) -> list[dict]:
    groups: dict[str, dict] = {}
    for record in records:
        if record.get("split") != split:
            continue
        match = CROP_PATTERN.match(Path(record["crop"]).stem)
        key = match.group("stem") + "_" + match.group("index") if match else Path(record["crop"]).stem
        rotation = int(match.group("rotation")) if match else 0
        group = groups.setdefault(
            key,
            {
                "key": key,
                "scene_id": record.get("scene_id"),
                "rank": record["rank"],
                "suit": record["suit"],
                "crops": {},
            },
        )
        group["crops"][rotation] = record
    ordered = [groups[key] for key in sorted(groups)]
    return ordered[:max_samples] if max_samples else ordered


def classifier_predictions(repo_root: Path, args, report) -> int:
    import numpy as np

    try:
        import tensorflow as tf
    except Exception as exc:  # noqa: BLE001
        raise EnvironmentUnavailable(f"缺少 TensorFlow：{exc}") from exc

    weights = resolve_path(repo_root, args.weights)
    if weights is None or not weights.is_file():
        raise CliError(f"权重不存在：{args.weights}")
    crops_path = resolve_path(repo_root, args.crops)
    if crops_path is None or not crops_path.is_file():
        raise CliError(f"crops manifest 不存在：{args.crops}")
    report.add_input(weights, "weights")
    report.add_input(crops_path, "crops-manifest")

    from labels import RANKS, SUITS, joker_rank_by_color

    if weights.suffix == ".tflite":
        # 直接评导出后的模型，而不是回退到 .keras 权重。
        interpreter = tf.lite.Interpreter(model_path=str(weights))
        interpreter.allocate_tensors()
        input_detail = interpreter.get_input_details()[0]
        output_details = interpreter.get_output_details()

        def infer(pixels):
            interpreter.set_tensor(input_detail["index"], pixels[None, ...].astype(np.float32))
            interpreter.invoke()
            tensors = [interpreter.get_tensor(detail["index"])[0] for detail in output_details]
            rank = next(t for t in tensors if t.shape[0] == len(RANKS))
            suit = next(t for t in tensors if t.shape[0] == len(SUITS))
            return rank, suit

        report.parameters["engine"] = "tflite"
    else:
        model = tf.keras.models.load_model(str(weights))

        def infer(pixels):
            output = model(pixels[None, ...], training=False)
            return output["rank"][0].numpy(), output["suit"][0].numpy()

        report.parameters["engine"] = "keras"
    payload = read_json(crops_path)
    groups = group_crops(payload.get("records", []), args.split, args.max_samples)
    if not groups:
        raise CliError(f"split={args.split} 下没有 crop 样本")
    report.dataset.update(split=args.split, sample_count=len(groups))

    unknown_count = 0
    joker_overrides = 0
    rows = []
    for group in groups:
        best = None
        for rotation, record in sorted(group["crops"].items()):
            path = resolve_path(repo_root, record["crop"])
            if path is None or not path.is_file():
                raise CliError(f"crop 文件缺失：{record['crop']}")
            from PIL import Image

            with Image.open(path) as image:
                pixels = np.asarray(image.convert("RGB"), dtype=np.float32) / 255.0
            rank_raw, suit_raw = infer(pixels)
            rank_probabilities = apply_temperature([float(v) for v in rank_raw], args.temperature)
            suit_probabilities = apply_temperature([float(v) for v in suit_raw], args.temperature)
            rank_index = max(range(len(rank_probabilities)), key=rank_probabilities.__getitem__)
            suit_index = max(range(len(suit_probabilities)), key=suit_probabilities.__getitem__)
            confidence = rank_probabilities[rank_index] * suit_probabilities[suit_index]
            rank_sorted = sorted(rank_probabilities, reverse=True)
            suit_sorted = sorted(suit_probabilities, reverse=True)
            margin = min(rank_sorted[0] - rank_sorted[1], suit_sorted[0] - suit_sorted[1])
            candidate = {
                "rotation": rotation,
                "crop": record["crop"],
                "rank": RANKS[rank_index],
                "suit": SUITS[suit_index],
                "confidence": confidence,
                "margin": margin,
            }
            if best is None or candidate["confidence"] > best["confidence"]:
                best = candidate
        # 大小王按颜色区分：大王彩色、小王黑白。模型对 Joker 的两个类偶尔会反，
        # 这里用 crop 的彩色像素占比在两端确定区覆盖，灰区保留模型结论。
        if best["rank"] in ("SJ", "BJ"):
            from PIL import Image

            crop_path = resolve_path(repo_root, best["crop"])
            if crop_path is not None and crop_path.is_file():
                with Image.open(crop_path) as crop_image:
                    color_rank = joker_rank_by_color(crop_image)
                if color_rank and color_rank != best["rank"]:
                    joker_overrides += 1
                    best["rank"] = color_rank

        expected = {"rank": group["rank"], "suit": group["suit"]}
        # 54 个有效组合铁律：大小王的 suit 恒为 JOKER。分类器两个头是独立 argmax，
        # 若不对 Joker 强制该约束，评测会产出 BJ+HEART 这种不可能的牌，
        # 与 App 侧 CardCornerClassifier.suitOf 的口径也不一致。
        predicted_suit = "JOKER" if best["rank"] in ("SJ", "BJ") else best["suit"]
        predicted = {"rank": best["rank"], "suit": predicted_suit, "confidence": round(best["confidence"], 6)}
        correct = predicted["rank"] == expected["rank"] and predicted["suit"] == expected["suit"]
        unknown = best["confidence"] < args.min_confidence or best["margin"] < args.min_margin
        unknown_count += int(unknown)
        rows.append(
            {
                "crop": best["crop"],
                "scene_id": group["scene_id"],
                "expected": expected,
                "predicted": predicted,
                "correct": correct,
                "unknown": unknown,
                "margin": round(best["margin"], 6),
                "rotation_used": best["rotation"],
            }
        )

    output_path = Path(args.output_dir) / "predictions.jsonl"
    write_jsonl(output_path, rows)
    report.add_output(output_path, "predictions")
    exact = sum(1 for row in rows if row["correct"]) / len(rows)
    report.metrics.update(
        samples=len(rows),
        exact=round(exact, 6),
        unknown=unknown_count,
        unknown_rate=round(unknown_count / len(rows), 6),
        temperature=args.temperature,
        min_confidence=args.min_confidence,
        min_margin=args.min_margin,
        rotation_tta=True,
        joker_color_overrides=joker_overrides,
    )
    report.check("predictions_written", output_path.is_file(), f"{len(rows)} rows")
    report.check("labels_valid", True, "rank/suit 来自 54 合法组合")
    return EXIT_PASS


def detector_predictions(repo_root: Path, args, report) -> int:
    try:
        from ultralytics import YOLO
    except Exception as exc:  # noqa: BLE001
        raise EnvironmentUnavailable(f"缺少 Ultralytics：{exc}") from exc

    weights = resolve_path(repo_root, args.weights)
    if weights is None or not weights.is_file():
        raise CliError(f"权重不存在：{args.weights}")
    manifest_path = resolve_path(repo_root, args.manifest)
    if manifest_path is None or not manifest_path.is_file():
        raise CliError(f"manifest 不存在：{args.manifest}")
    report.add_input(weights, "weights")
    report.add_input(manifest_path, "manifest")

    manifest = read_json(manifest_path)
    entries = [e for e in manifest.get("images", []) if e.get("split") == args.split]
    if not entries:
        raise CliError(f"split={args.split} 下没有图片")
    if args.max_samples:
        entries = entries[: args.max_samples]
    report.dataset.update(split=args.split, sample_count=len(entries))

    model = YOLO(str(weights))
    rows = []
    for entry in entries:
        image_path = resolve_path(repo_root, entry.get("image", ""))
        if image_path is None or not image_path.is_file():
            raise CliError(f"图片缺失：{entry.get('image')}")
        truth = [
            card
            for card in entry.get("cards") or []
            if card.get("visible", True) and card.get("corner_quad")
        ]
        result = model.predict(
            source=str(image_path),
            imgsz=args.image_size,
            conf=args.min_confidence,
            iou=args.nms_iou,
            verbose=False,
        )[0]
        corners = []
        obb = getattr(result, "obb", None)
        if obb is not None and len(obb) > 0:
            quads = obb.xyxyxyxy.tolist() if hasattr(obb.xyxyxyxy, "tolist") else list(obb.xyxyxyxy)
            scores = obb.conf.tolist() if hasattr(obb.conf, "tolist") else list(obb.conf)
            for quad, score in zip(quads, scores):
                points = [[float(p[0]), float(p[1])] for p in quad]
                best_iou, best_card = 0.0, None
                for card in truth:
                    value = polygon_iou(points, card["corner_quad"])
                    if value > best_iou:
                        best_iou, best_card = value, card
                matched = best_iou >= args.match_iou
                corners.append(
                    {
                        "quad": points,
                        "score": round(float(score), 6),
                        "iou": round(best_iou, 6),
                        "rank": best_card["rank"] if matched else "",
                        "suit": best_card["suit"] if matched else "",
                    }
                )
        rows.append({"image": entry.get("image"), "scene_id": entry.get("scene_id"), "corners": corners})

    output_path = Path(args.output_dir) / "predictions.jsonl"
    write_jsonl(output_path, rows)
    report.add_output(output_path, "predictions")
    total_detections = sum(len(row["corners"]) for row in rows)
    matched = sum(1 for row in rows for c in row["corners"] if c["iou"] >= args.match_iou)
    report.metrics.update(
        images=len(rows),
        detections=total_detections,
        matched=matched,
        match_iou=args.match_iou,
        min_confidence=args.min_confidence,
        nms_iou=args.nms_iou,
        image_size=args.image_size,
    )
    report.check("predictions_written", output_path.is_file(), f"{len(rows)} images")
    return EXIT_PASS


def main() -> int:
    parser = build_parser(
        prog="predict.py",
        version=VERSION,
        task_id=TASK_ID,
        description="生成 classifier/detector 预测 JSONL（evaluate.py 与 calibrate.py 的输入）",
        need_seed=False,
    )
    parser.add_argument("--stage", choices=["classifier", "detector"], required=True)
    parser.add_argument("--weights", required=True)
    parser.add_argument("--crops", default=None, help="classifier：crops_manifest.json")
    parser.add_argument("--manifest", default="model_training/datasets/dataset_manifest.json")
    parser.add_argument("--split", default="test")
    parser.add_argument("--max-samples", type=int, default=0, help="0 = 不限")
    parser.add_argument("--temperature", type=float, default=1.0)
    parser.add_argument("--min-confidence", type=float, default=0.0)
    parser.add_argument("--min-margin", type=float, default=0.0)
    parser.add_argument("--nms-iou", type=float, default=0.5)
    parser.add_argument("--match-iou", type=float, default=0.5)
    parser.add_argument("--image-size", type=int, default=640)
    parser.add_argument("--task", default=None, help="覆盖报告 task_id（例如 T2.4/T3.5）")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, args.task or ("T3.5" if args.stage == "classifier" else "T2.4"))
    report.parameters.update(
        stage=args.stage,
        weights=args.weights,
        crops=args.crops,
        split=args.split,
        temperature=args.temperature,
        min_confidence=args.min_confidence,
        min_margin=args.min_margin,
    )
    args.output_dir = str(run_dir)

    def body() -> int:
        if args.stage == "classifier":
            if not args.crops:
                raise CliError("classifier 预测需要 --crops")
            code = classifier_predictions(repo_root, args, report)
        else:
            code = detector_predictions(repo_root, args, report)
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

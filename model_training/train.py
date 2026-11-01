"""点数/花色双头分类器训练与导出（T3.2 / T3.4）。

两种数据来源：

- `--crops RUN/crops_manifest.json`：prepare_crops.py 产出（推荐，manifest 驱动）。
- `--annotations model_training/annotations.json --images testcases`：旧的人工标注流程。

`--smoke` 只跑 1 epoch 的小样本链路验证，状态固定为 NOT_RUN —— 冒烟不等于训练完成。
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
from labels import RANKS, SUITS, validate_label

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
)

VERSION = "2.0.0"
TASK_ID = "T3.2"

INPUT_WIDTH = 96
INPUT_HEIGHT = 128


def load_records_from_crops(crops_manifest: Path, orientations: list[int] | None = None) -> list[dict]:
    payload = read_json(crops_manifest)
    records = []
    for record in payload.get("records", []):
        # prepare_crops.py 对每张牌同时出 0°/180° 两个 crop，但标签是同一个：
        # 180° 的牌面是倒的（"6" 倒过来就是 "9"），直接混训等于往标签里注噪声。
        # 这里只保留指定方向；朝向鲁棒性由 predict.py 的 0°/180° 取较优负责。
        rotation = record.get("rotation")
        if orientations is not None and rotation is not None and rotation not in orientations:
            continue
        validate_label(record["rank"], record["suit"])
        records.append(
            {
                "path": record["crop"],
                "sha256": record.get("sha256"),
                "rank": record["rank"],
                "suit": record["suit"],
                "split": record.get("split", "train"),
                "scene_id": record.get("scene_id"),
            }
        )
    return records


def load_records_from_annotations(annotations: Path, images_dir: Path) -> list[dict]:
    data = json.loads(annotations.read_text(encoding="utf-8"))
    records = []
    pending = 0
    for image_name, boxes in data["images"].items():
        path = images_dir / image_name
        if not path.exists():
            raise FileNotFoundError(path)
        for box in boxes:
            if box.get("needs_review"):
                pending += 1
                continue
            validate_label(box["rank"], box["suit"])
            records.append({"path": str(path), **box, "split": "train"})
    if pending:
        raise CliError(f"{pending} 条自动标注仍未人工确认，拒绝训练")
    return records


def record_quad(record: dict) -> list[list[float]]:
    if "quad" in record:
        quad = record["quad"]
        if len(quad) != 4 or any(len(point) != 2 for point in quad):
            raise CliError(f"非法四点：{quad}")
        return quad
    x, y, w, h = record["bbox"]
    return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]


def rectify(image, quad, width: int, height: int):
    from PIL import Image

    destination = [[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]]
    matrix, values = [], []
    for (x, y), (u, v) in zip(destination, quad):
        matrix += [[x, y, 1, 0, 0, 0, -u * x, -u * y], [0, 0, 0, x, y, 1, -v * x, -v * y]]
        values += [u, v]
    coefficients = np.linalg.solve(np.asarray(matrix, dtype=np.float64), np.asarray(values, dtype=np.float64))
    return image.transform((width, height), Image.Transform.PERSPECTIVE, coefficients, Image.Resampling.BICUBIC)


def create_model(tf, imagenet: bool, config: dict, learning_rate=None):
    inputs = tf.keras.Input((INPUT_HEIGHT, INPUT_WIDTH, 3), name="card_corner")
    augmented = tf.keras.Sequential(
        [
            tf.keras.layers.RandomRotation(config.get("augmentation", {}).get("rotation_deg", 3.5) / 180.0),
            tf.keras.layers.RandomTranslation(0.05, 0.05),
            tf.keras.layers.RandomZoom(0.08),
            tf.keras.layers.RandomContrast(0.18),
            tf.keras.layers.Rescaling(2.0, offset=-1.0),
        ],
        name="augmentation",
    )(inputs)
    backbone = tf.keras.applications.MobileNetV3Small(
        input_shape=(INPUT_HEIGHT, INPUT_WIDTH, 3),
        include_top=False,
        weights="imagenet" if imagenet else None,
        pooling="avg",
        include_preprocessing=False,
    )
    features = tf.keras.layers.Dropout(float(config.get("dropout", 0.25)))(backbone(augmented))
    rank = tf.keras.layers.Dense(len(RANKS), activation="softmax", name="rank")(features)
    suit = tf.keras.layers.Dense(len(SUITS), activation="softmax", name="suit")(features)
    model = tf.keras.Model(inputs, {"rank": rank, "suit": suit})
    model.compile(
        optimizer=tf.keras.optimizers.Adam(
            learning_rate if learning_rate is not None else float(config.get("learning_rate", 2e-4))
        ),
        loss={"rank": "sparse_categorical_crossentropy", "suit": "sparse_categorical_crossentropy"},
        metrics={"rank": ["accuracy"], "suit": ["accuracy"]},
    )
    return model


def samples(records: list[dict]):
    from PIL import Image

    for record in records:
        with Image.open(record["path"]) as image:
            crop = image.convert("RGB")
            if crop.size != (INPUT_WIDTH, INPUT_HEIGHT):
                crop = rectify(crop, record_quad(record), INPUT_WIDTH, INPUT_HEIGHT) if "quad" in record else crop.resize(
                    (INPUT_WIDTH, INPUT_HEIGHT)
                )
            pixels = np.asarray(crop, dtype=np.float32) / 255.0
        yield pixels, {"rank": np.int32(RANKS.index(record["rank"])), "suit": np.int32(SUITS.index(record["suit"]))}


def dataset(tf, records: list[dict], batch_size: int, shuffle: bool):
    signature = (
        tf.TensorSpec((INPUT_HEIGHT, INPUT_WIDTH, 3), tf.float32),
        {"rank": tf.TensorSpec((), tf.int32), "suit": tf.TensorSpec((), tf.int32)},
    )
    ds = tf.data.Dataset.from_generator(lambda: samples(records), output_signature=signature)
    if shuffle:
        ds = ds.shuffle(max(32, len(records)), reshuffle_each_iteration=True)
    return ds.batch(batch_size).prefetch(tf.data.AUTOTUNE)


def exact_accuracy(tf, model, records: list[dict], batch_size: int) -> float | None:
    if not records:
        return None
    correct = total = 0
    for images, labels in dataset(tf, records, batch_size, False):
        output = model(images, training=False)
        rank_ok = tf.argmax(output["rank"], axis=1, output_type=tf.int32) == labels["rank"]
        suit_ok = tf.argmax(output["suit"], axis=1, output_type=tf.int32) == labels["suit"]
        correct += int(tf.reduce_sum(tf.cast(rank_ok & suit_ok, tf.int32)))
        total += int(images.shape[0])
    return correct / total if total else None


def main() -> int:
    parser = build_parser(
        prog="train.py",
        version=VERSION,
        task_id=TASK_ID,
        description="训练/导出 MobileNetV3Small 双头分类器（15 点数 × 5 花色，合法组合 54）",
        need_seed=True,
    )
    parser.add_argument("--mode", choices=["train", "export"], default="train")
    parser.add_argument("--manifest", default="model_training/datasets/dataset_manifest.json")
    parser.add_argument("--crops", default=None)
    parser.add_argument("--annotations", default=None)
    parser.add_argument("--images", default="testcases")
    parser.add_argument("--weights", default=None)
    parser.add_argument("--formats", default="float16")
    parser.add_argument("--epochs", type=int, default=None)
    parser.add_argument("--batch-size", type=int, default=None)
    parser.add_argument("--no-imagenet", action="store_true")
    parser.add_argument(
        "--orientations", nargs="+", type=int, default=None, help="只用这些方向的 crop 训练（默认取 config 的 orientations）"
    )
    parser.add_argument("--smoke", action="store_true", help="1 epoch 小样本链路冒烟（状态固定 NOT_RUN）")
    parser.add_argument("--task", default=None, help="覆盖报告 task_id（例如 T3.4）")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, args.task or TASK_ID)
    config = load_config(repo_root, args.config) if args.config else load_config(repo_root, "model_training/configs/classifier.yaml")
    report.parameters.update(
        mode=args.mode, crops=args.crops, annotations=args.annotations, formats=args.formats, smoke=args.smoke
    )

    def body() -> int:
        try:
            import tensorflow as tf
        except Exception as exc:  # noqa: BLE001
            raise EnvironmentUnavailable(f"缺少 TensorFlow，无法训练/导出：{exc}") from exc
        tf.keras.utils.set_random_seed(args.seed)

        if args.mode == "export":
            if not args.weights:
                raise CliError("export 模式需要 --weights")
            weights = resolve_path(repo_root, args.weights)
            if weights is None or not weights.is_file():
                raise CliError(f"权重不存在：{args.weights}")
            report.add_input(weights, "weights")
            model = tf.keras.models.load_model(str(weights))
            export_dir = run_dir / "export"
            export_dir.mkdir(parents=True, exist_ok=True)
            # 保留 SavedModel 便于追溯；但转换必须用 from_keras_model：
            # TF 2.19 在这台 macOS 上 from_saved_model().convert() 会在 MLIR 阶段原生崩溃
            # （进程直接被杀，连 report 都写不出来），from_keras_model 则正常。
            model.export(export_dir / "saved_model")
            converter = tf.lite.TFLiteConverter.from_keras_model(model)
            if "float16" in args.formats:
                converter.optimizations = [tf.lite.Optimize.DEFAULT]
                converter.target_spec.supported_types = [tf.float16]
            payload = converter.convert()
            model_path = export_dir / "classifier.tflite"
            model_path.write_bytes(payload)
            manifest = {
                "version": f"classifier-{sha256_file(model_path)[:12]}",
                "model_sha256": sha256_file(model_path),
                "input": [1, INPUT_HEIGHT, INPUT_WIDTH, 3],
                "normalization": "RGB float32 / 255",
                "outputs": {"rank": len(RANKS), "suit": len(SUITS)},
                "rank_labels": RANKS,
                "suit_labels": SUITS,
                "formats": args.formats,
                "seed": args.seed,
            }
            write_json(export_dir / "model_manifest.json", manifest)
            report.add_output(model_path, "model")
            report.add_output(export_dir / "model_manifest.json", "manifest")
            report.check("export_size", len(payload) > 0, f"{len(payload)} bytes")
        else:
            orientations = args.orientations if args.orientations is not None else config.get("orientations", [0])
            report.parameters["orientations"] = orientations
            if args.crops:
                records = load_records_from_crops(resolve_path(repo_root, args.crops), orientations)
                report.add_input(resolve_path(repo_root, args.crops), "crops-manifest")
            elif args.annotations:
                records = load_records_from_annotations(
                    resolve_path(repo_root, args.annotations), resolve_path(repo_root, args.images)
                )
            else:
                raise CliError("train 模式需要 --crops 或 --annotations")
            train_records = [r for r in records if r.get("split") in {"train", None, ""}]
            val_records = [r for r in records if r.get("split") == "val"]
            labels = {(r["rank"], r["suit"]) for r in train_records}
            if len(labels) < 54:
                report.check("all_54_labels_in_train", False, f"{len(labels)}/54")
            else:
                report.check("all_54_labels_in_train", True, "54/54")
            report.check("train_split_used", len(train_records) > 0, f"{len(train_records)} crops")
            if args.smoke:
                train_records = train_records[:64]
                val_records = val_records[:16]
            report.dataset.update(
                version=str(resolve_path(repo_root, args.crops).name if args.crops else "annotations"),
                split="train+val",
                sample_count=len(train_records) + len(val_records),
            )
            epochs = 1 if args.smoke else (args.epochs or int(config.get("epochs", 35)))
            batch_size = args.batch_size or int(config.get("batch_size", 24))
            # 从 imagenet 权重微调时固定学习率会在后期掍动，用 cosine 衰减提最后几个点。
            steps = max(1, math.ceil(len(train_records) / batch_size)) * epochs
            schedule = tf.keras.optimizers.schedules.CosineDecay(
                float(config.get("learning_rate", 2e-4)), decay_steps=steps, alpha=0.02
            )
            model = create_model(tf, not args.no_imagenet, config, schedule)
            model.fit(
                dataset(tf, train_records, batch_size, True),
                validation_data=dataset(tf, val_records, batch_size, False) if val_records else None,
                epochs=epochs,
                callbacks=[
                    tf.keras.callbacks.EarlyStopping(
                        monitor="val_loss" if val_records else "loss", patience=6, restore_best_weights=True
                    )
                ],
            )
            accuracy = exact_accuracy(tf, model, val_records, batch_size)
            model.save(run_dir / "best.keras")
            report.add_output(run_dir / "best.keras", "weights")
            report.metrics.update(
                train_samples=len(train_records),
                val_samples=len(val_records),
                validation_exact=accuracy,
                epochs=epochs,
                class_weight="none（crop 已按场景均衡）",
            )
            if args.smoke:
                report.warn("smoke run：只验证训练链路，不作为训练完成证据")
                return emit(report, STATUS_NOT_RUN, 3, args)

        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

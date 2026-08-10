"""Train and export a two-head rank/suit LiteRT classifier from annotated card corners."""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

from labels import RANKS, SUITS, validate_label

INPUT_WIDTH = 96
INPUT_HEIGHT = 128


def load_records(annotations: Path, images_dir: Path) -> list[dict]:
    data = json.loads(annotations.read_text(encoding="utf-8"))
    records = []
    pending_review = 0
    for image_name, boxes in data["images"].items():
        path = images_dir / image_name
        if not path.exists():
            raise FileNotFoundError(path)
        for box in boxes:
            if box.get("needs_review"):
                pending_review += 1
                continue
            validate_label(box["rank"], box["suit"])
            records.append({"image": path, **box})
    if pending_review:
        raise ValueError(f"{pending_review} automatic annotations still need manual confirmation in annotate.py")
    return records


def split_by_image(records: list[dict], validation_fraction: float, seed: int) -> tuple[list[dict], list[dict]]:
    names = sorted({str(r["image"]) for r in records})
    random.Random(seed).shuffle(names)
    count = max(1, round(len(names) * validation_fraction)) if len(names) > 1 else 0
    validation_names = set(names[:count])
    train = [r for r in records if str(r["image"]) not in validation_names]
    validation = [r for r in records if str(r["image"]) in validation_names]
    return train, validation


def samples(records: list[dict]):
    for record in records:
        with Image.open(record["image"]) as image:
            image = image.convert("RGB")
            quad = record_quad(record)
            crop = rectify(image, quad, INPUT_WIDTH, INPUT_HEIGHT)
            pixels = np.asarray(crop, dtype=np.float32) / 255.0
        yield pixels, {"rank": np.int32(RANKS.index(record["rank"])), "suit": np.int32(SUITS.index(record["suit"]))}


def record_quad(record: dict) -> list[list[float]]:
    if "quad" in record:
        quad = record["quad"]
        if len(quad) != 4 or any(len(point) != 2 for point in quad):
            raise ValueError(f"Invalid quadrilateral: {quad}")
        return quad
    x, y, w, h = record["bbox"]
    return [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]


def rectify(image: Image.Image, quad: list[list[float]], width: int, height: int) -> Image.Image:
    """Perspective-correct TL,TR,BR,BL source points into an upright model input."""
    destination = [[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]]
    matrix = []
    values = []
    for (x, y), (u, v) in zip(destination, quad):
        matrix += [[x, y, 1, 0, 0, 0, -u * x, -u * y], [0, 0, 0, x, y, 1, -v * x, -v * y]]
        values += [u, v]
    coefficients = np.linalg.solve(np.asarray(matrix, dtype=np.float64), np.asarray(values, dtype=np.float64))
    return image.transform((width, height), Image.Transform.PERSPECTIVE, coefficients, Image.Resampling.BICUBIC)


def dataset(records: list[dict], batch_size: int, shuffle: bool) -> tf.data.Dataset:
    signature = (
        tf.TensorSpec((INPUT_HEIGHT, INPUT_WIDTH, 3), tf.float32),
        {"rank": tf.TensorSpec((), tf.int32), "suit": tf.TensorSpec((), tf.int32)},
    )
    ds = tf.data.Dataset.from_generator(lambda: samples(records), output_signature=signature)
    if shuffle:
        ds = ds.shuffle(max(32, len(records)), reshuffle_each_iteration=True)
    return ds.batch(batch_size).prefetch(tf.data.AUTOTUNE)


def create_model(imagenet: bool) -> tf.keras.Model:
    inputs = tf.keras.Input((INPUT_HEIGHT, INPUT_WIDTH, 3), name="card_corner")
    augmented = tf.keras.Sequential([
        tf.keras.layers.RandomRotation(.06),
        tf.keras.layers.RandomTranslation(.05, .05),
        tf.keras.layers.RandomZoom(.08),
        tf.keras.layers.RandomContrast(.18),
        tf.keras.layers.Rescaling(2.0, offset=-1.0),
    ], name="augmentation")(inputs)
    backbone = tf.keras.applications.MobileNetV3Small(
        input_shape=(INPUT_HEIGHT, INPUT_WIDTH, 3), include_top=False,
        weights="imagenet" if imagenet else None, pooling="avg", include_preprocessing=False,
    )
    features = backbone(augmented)
    features = tf.keras.layers.Dropout(.25)(features)
    rank = tf.keras.layers.Dense(len(RANKS), activation="softmax", name="rank")(features)
    suit = tf.keras.layers.Dense(len(SUITS), activation="softmax", name="suit")(features)
    model = tf.keras.Model(inputs, {"rank": rank, "suit": suit})
    model.compile(
        optimizer=tf.keras.optimizers.Adam(2e-4),
        loss={"rank": "sparse_categorical_crossentropy", "suit": "sparse_categorical_crossentropy"},
        metrics={"rank": ["accuracy"], "suit": ["accuracy"]},
    )
    return model


def exact_accuracy(model: tf.keras.Model, validation: list[dict], batch_size: int) -> float | None:
    if not validation:
        return None
    correct = total = 0
    for images, labels in dataset(validation, batch_size, False):
        output = model(images, training=False)
        rank_ok = tf.argmax(output["rank"], axis=1, output_type=tf.int32) == labels["rank"]
        suit_ok = tf.argmax(output["suit"], axis=1, output_type=tf.int32) == labels["suit"]
        correct += int(tf.reduce_sum(tf.cast(rank_ok & suit_ok, tf.int32)))
        total += int(images.shape[0])
    return correct / total


def main() -> None:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--annotations", type=Path, default=here / "annotations.json")
    parser.add_argument("--images", type=Path, default=here.parent / "testcases")
    parser.add_argument("--output", type=Path, default=here / "output")
    parser.add_argument("--epochs", type=int, default=35)
    parser.add_argument("--batch-size", type=int, default=24)
    parser.add_argument("--validation-fraction", type=float, default=.2)
    parser.add_argument("--seed", type=int, default=20260810)
    parser.add_argument("--no-imagenet", action="store_true", help="Do not download ImageNet initialization")
    args = parser.parse_args()

    global np, tf, Image
    import numpy as np
    import tensorflow as tf
    from PIL import Image

    tf.keras.utils.set_random_seed(args.seed)
    records = load_records(args.annotations, args.images)
    if len(records) < 80:
        raise SystemExit(f"Only {len(records)} card corners are annotated; annotate at least 80 before training")
    train_records, validation_records = split_by_image(records, args.validation_fraction, args.seed)
    args.output.mkdir(parents=True, exist_ok=True)
    model = create_model(not args.no_imagenet)
    callbacks = [
        tf.keras.callbacks.EarlyStopping(monitor="val_loss" if validation_records else "loss", patience=6, restore_best_weights=True),
        tf.keras.callbacks.ModelCheckpoint(args.output / "best.keras", monitor="val_loss" if validation_records else "loss", save_best_only=True),
    ]
    model.fit(
        dataset(train_records, args.batch_size, True),
        validation_data=dataset(validation_records, args.batch_size, False) if validation_records else None,
        epochs=args.epochs,
        callbacks=callbacks,
    )
    model.export(args.output / "saved_model")
    converter = tf.lite.TFLiteConverter.from_saved_model(str(args.output / "saved_model"))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    (args.output / "card_corner_classifier.tflite").write_bytes(converter.convert())
    report = {
        "input": [1, INPUT_HEIGHT, INPUT_WIDTH, 3],
        "normalization": "RGB float32 / 255",
        "model_preprocessing": "Rescaling input from [0,1] to [-1,1]",
        "outputs": {"rank": len(RANKS), "suit": len(SUITS)},
        "rank_labels": RANKS,
        "suit_labels": SUITS,
        "training_samples": len(train_records),
        "validation_samples": len(validation_records),
        "validation_exact_card_accuracy": exact_accuracy(model, validation_records, args.batch_size),
        "seed": args.seed,
    }
    (args.output / "model_manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

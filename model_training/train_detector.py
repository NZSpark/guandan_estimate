"""Train the stage-one oriented card-corner detector and export it to LiteRT/TFLite."""

from __future__ import annotations

import argparse
from pathlib import Path


def main() -> None:
    here = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=here / "output" / "obb_dataset" / "data.yaml")
    parser.add_argument("--model", default="yolo11n-obb.pt")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--image-size", type=int, default=640)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--output", type=Path, default=here / "output" / "detector_runs")
    args = parser.parse_args()

    from ultralytics import YOLO

    if not args.data.exists():
        raise SystemExit(f"Missing {args.data}; run export_obb.py first")
    model = YOLO(args.model)
    result = model.train(
        data=str(args.data), epochs=args.epochs, imgsz=args.image_size, batch=args.batch_size,
        project=str(args.output), name="card_corner_obb", patience=15, degrees=15,
        perspective=.0008, translate=.08, scale=.25, fliplr=0.0, flipud=0.0,
    )
    best = Path(result.save_dir) / "weights" / "best.pt"
    exported = YOLO(str(best)).export(format="tflite", imgsz=args.image_size, half=True)
    print(f"Best detector: {best}")
    print(f"Exported TFLite: {exported}")


if __name__ == "__main__":
    main()

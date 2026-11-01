"""独立进程里的 LiteRT 导出（T2.3 的实际转换器）。

为什么单独一个进程：ultralytics 8.4.195 的 `export(format='litert')` 与在本 CLI 进程里
直接 `litert_torch.convert` 都会 SIGSEGV(139)，而同样的代码在一个干净的子进程里 8 秒完成。
把转换隔离出去后，崩溃可以由父进程检测并如实记为 FAIL，而不是让整份报告凭空消失。

用法：`python model_training/litert_export_worker.py --weights best.pt --output card_corner.tflite --image-size 640`
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


def export_litert_direct(weights: Path, output: Path, image_size: int = 640) -> Path:
    import torch
    import litert_torch

    checkpoint = torch.load(str(weights), map_location="cpu", weights_only=False)
    model = checkpoint["model"].float().eval()
    try:
        model = model.fuse()
    except Exception:  # noqa: BLE001 - fuse 只影响速度，失败不影响导出
        pass
    edge = litert_torch.convert(model, (torch.zeros(1, 3, image_size, image_size),))
    output.parent.mkdir(parents=True, exist_ok=True)
    edge.export(str(output))
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description="在独立进程里把 YOLO-OBB 权重导成 LiteRT/TFLite")
    parser.add_argument("--weights", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--image-size", type=int, default=640)
    args = parser.parse_args()
    output = export_litert_direct(Path(args.weights), Path(args.output), args.image_size)
    if not output.is_file() or output.stat().st_size == 0:
        print(f"导出结果为空：{output}", file=sys.stderr)
        return 1
    print(f"EXPORTED {output} {output.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

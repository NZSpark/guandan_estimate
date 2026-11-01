"""生成程序化背景纹理，供合成场景使用（T1.3 素材，无第三方版权）。

固定 seed 与配置可完全复现；输出目录含 index.json 记录每个背景的 seed、尺寸和许可。
"""

from __future__ import annotations

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
)

VERSION = "1.0.0"
TASK_ID = "T1.3"


def make_background(seed: int, width: int, height: int):
    from PIL import Image, ImageDraw, ImageFilter

    rng = random.Random(seed)
    base = Image.new("RGB", (width, height), tuple(rng.randrange(30, 210) for _ in range(3)))
    draw = ImageDraw.Draw(base)
    palette = [tuple(rng.randrange(0, 256) for _ in range(3)) for _ in range(6)]
    for _ in range(rng.randrange(40, 140)):
        x, y = rng.randrange(width), rng.randrange(height)
        radius = rng.randrange(6, max(8, width // 6))
        draw.ellipse(
            [x - radius, y - radius, x + radius, y + radius],
            fill=rng.choice(palette),
        )
    for _ in range(rng.randrange(20, 90)):
        x, y = rng.randrange(width), rng.randrange(height)
        length = rng.randrange(20, width // 3)
        angle = rng.uniform(-0.6, 0.6)
        draw.line(
            [x, y, x + length, y + int(length * angle)],
            fill=rng.choice(palette),
            width=rng.randrange(1, 4),
        )
    if rng.random() < 0.7:
        base = base.filter(ImageFilter.GaussianBlur(rng.uniform(0.5, 2.5)))
    return base


def main() -> int:
    parser = build_parser(
        prog="generate_backgrounds.py",
        version=VERSION,
        task_id=TASK_ID,
        description="生成程序化背景纹理（self-generated，无第三方素材）",
        need_seed=True,
    )
    parser.add_argument("--count", type=int, default=24)
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=960)
    parser.add_argument("--out", default="model_training/datasets/backgrounds")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(
        count=args.count, width=args.width, height=args.height, out=args.out, seed=args.seed
    )

    def body() -> int:
        from PIL import Image

        if args.count <= 0:
            raise CliError("--count 必须大于 0")
        out_dir = Path(args.out)
        if not out_dir.is_absolute():
            out_dir = repo_root / out_dir
        out_dir.mkdir(parents=True, exist_ok=True)
        index = []
        for index_value in range(args.count):
            background_seed = args.seed + index_value
            image = make_background(background_seed, args.width, args.height)
            path = out_dir / f"bg_{background_seed:06d}.png"
            image.save(path, format="PNG", optimize=True)
            index.append(
                {
                    "path": f"model_training/datasets/backgrounds/{path.name}",
                    "seed": background_seed,
                    "width": args.width,
                    "height": args.height,
                    "license": "self-generated",
                    "source_id": "procedural-backgrounds",
                }
            )
        index_path = out_dir / "index.json"
        write_json(
            index_path,
            {"schema_version": 1, "generator": "generate_backgrounds.py", "seed": args.seed, "backgrounds": index},
        )
        report.dataset = {
            "version": f"backgrounds-seed{args.seed}",
            "manifest_sha256": "",
            "split": "assets",
            "sample_count": len(index),
        }
        report.metrics["backgrounds"] = len(index)
        report.check("sample_count_positive", len(index) > 0, f"{len(index)} backgrounds")
        report.check("seed_recorded", args.seed is not None, f"seed={args.seed}")
        report.add_output(index_path, "index")
        determinism = make_background(args.seed, args.width, args.height).tobytes()
        again = make_background(args.seed, args.width, args.height).tobytes()
        report.check("deterministic_render", determinism == again, "same seed -> identical pixels")
        status = report.decide_status()
        exit_code = EXIT_PASS if status == STATUS_PASS else 1
        return emit(report, status, exit_code, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

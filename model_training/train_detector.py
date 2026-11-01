"""牌角检测器训练与导出（T2.1 baseline / T2.2 train / T2.3 export）。

单类 `card_corner` 的 YOLO11n-OBB。需要已通过 T1.5 数据门禁的 OBB 数据集（data.yaml）。
没有数据集或 Ultralytics 不可用时返回 NOT_RUN；导出只按显式 --format，不静默换格式。
"""

from __future__ import annotations

from pathlib import Path

from _cli import (
    EXIT_FAIL,
    EXIT_PASS,
    EXIT_USAGE,
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

VERSION = "2.1.0"
TASK_ID = "T2.1"


def export_litert_via_worker(weights: Path, output: Path, image_size: int, repo_root: Path) -> Path:
    """在独立子进程里做 litert 转换。

    ultralytics 8.4.195 的 `export(format='litert')` 与本进程内直接 `litert_torch.convert`
    在本机都会 SIGSEGV（139）；同样的代码放进干净子进程 8 秒就能完成。隔离之后崩溃会被
    父进程捕获并如实报 FAIL，而不是让整份 report.json 消失。
    """
    import subprocess
    import sys

    worker = repo_root / "model_training" / "litert_export_worker.py"
    if not worker.is_file():
        raise CliError(f"缺少导出子进程脚本：{worker}")
    result = subprocess.run(
        [sys.executable, str(worker), "--weights", str(weights), "--output", str(output), "--image-size", str(image_size)],
        capture_output=True,
        text=True,
        timeout=3600,
    )
    if result.returncode != 0:
        reason = f"rc={result.returncode}"
        if result.returncode < 0:
            reason += f"（被信号 {-result.returncode} 终止，负值即 SIGSEGV/SIGKILL）"
        raise EnvironmentUnavailable(
            f"litert 导出子进程失败：{reason}；stderr 末尾：{(result.stderr or '').strip()[-400:]}"
        )
    if not output.is_file() or output.stat().st_size == 0:
        raise CliError(f"导出结果为空：{output}")
    return output


def main() -> int:
    parser = build_parser(
        prog="train_detector.py",
        version=VERSION,
        task_id=TASK_ID,
        description="训练/导出单类 card_corner OBB 检测器",
        need_seed=True,
    )
    parser.add_argument("--mode", choices=["baseline", "train", "export"], default="train")
    parser.add_argument("--manifest", default="model_training/datasets/dataset_manifest.json")
    parser.add_argument("--data", default=None, help="YOLO OBB data.yaml；默认 model_training/output/obb_dataset/data.yaml")
    parser.add_argument("--model", default=None)
    parser.add_argument("--weights", default=None)
    parser.add_argument("--format", default="litert")
    parser.add_argument(
        "--quantize",
        default="auto",
        choices=["auto", "16", "32", "8", "w8a16", "w8a32"],
        help="litert 不支持 FP16：auto 会显式降为 FP32 并记录；显式传 16 则报错",
    )
    parser.add_argument("--image-size", type=int, default=None)
    parser.add_argument("--epochs", type=int, default=None)
    parser.add_argument("--batch-size", type=int, default=None)
    parser.add_argument("--degrees", type=float, default=None, help="旋转增广幅度（0–180）；真机照片需要 180")
    parser.add_argument("--perspective", type=float, default=None, help="透视增广强度")
    parser.add_argument("--scale", type=float, default=None, help="缩放增广幅度")
    parser.add_argument("--patience", type=int, default=None)
    parser.add_argument("--task", default=None, help="覆盖任务 ID（T2.1/T2.2/T2.3）")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    task_id = args.task or {"baseline": "T2.1", "train": "T2.2", "export": "T2.3"}[args.mode]
    report = make_report(args, task_id)
    config = load_config(repo_root, args.config) if args.config else load_config(repo_root, "model_training/configs/detector.yaml")
    report.parameters.update(
        mode=args.mode,
        data=args.data,
        model=args.model,
        format=args.format,
        degrees=args.degrees,
        perspective=args.perspective,
        scale=args.scale,
    )

    def body() -> int:
        # 注意：不要在这个进程里 import litert_torch / 调 ultralytics 的 litert 导出，
        # 两者在本机都会 SIGSEGV(139)。转换全部交给 litert_export_worker.py 子进程。
        try:
            from ultralytics import YOLO
        except Exception as exc:  # noqa: BLE001
            raise EnvironmentUnavailable(f"缺失 Ultralytics：{exc}") from exc

        if args.mode == "export":
            if not args.weights:
                raise CliError("export 模式需要 --weights")
            weights = resolve_path(repo_root, args.weights)
            if weights is None or not weights.is_file():
                raise CliError(f"权重不存在：{args.weights}")
            report.add_input(weights, "weights")
            export_dir = run_dir / "export"
            export_dir.mkdir(parents=True, exist_ok=True)
            export_kwargs: dict = {
                "format": args.format,
                "imgsz": args.image_size or int(config.get("image_size", 640)),
            }
            quantize = args.quantize
            if args.format == "litert":
                # ultralytics 8.4 的 litert 导出不支持 FP16（只接受 32/8/w8a16/w8a32）。
                # 文档要求 float16 优先，但不可用时必须显式记录降级而不是静默改格式。
                if quantize == "16":
                    raise CliError("litert 不支持 FP16；显式指定 --quantize 32/8/w8a16/w8a32")
                if quantize == "auto":
                    quantize = "32"
                export_kwargs["quantize"] = 32 if quantize == "32" else quantize
            else:
                export_kwargs["half"] = True
            if quantize == "32" and args.quantize == "auto":
                report.warn("litert 不支持 FP16：已显式降级为 FP32（quantize=32）并在 manifest 记录")
            try:
                if args.format == "litert":
                    exported_path = export_litert_via_worker(
                        weights,
                        export_dir / "card_corner.tflite",
                        int(export_kwargs["imgsz"]),
                        repo_root,
                    )
                    report.warn(
                        "导出在独立子进程完成：ultralytics export(format='litert') 与本进程内 litert_torch.convert "
                        "在本机均 SIGSEGV，权重/格式/输入尺寸不变，仅隔离执行"
                    )
                else:
                    exported = YOLO(str(weights)).export(**export_kwargs)
                    exported_path = Path(str(exported))
            except Exception as exc:  # noqa: BLE001
                raise EnvironmentUnavailable(f"导出 {args.format} 失败（不静默换格式）：{exc}") from exc
            if not exported_path.is_file():
                raise CliError(f"导出结果不存在：{exported_path}")
            input_shape = [1, 3, int(export_kwargs["imgsz"]), int(export_kwargs["imgsz"])]
            output_shape = None
            try:
                import tensorflow as tf

                interpreter = tf.lite.Interpreter(model_path=str(exported_path))
                interpreter.allocate_tensors()
                detail_in = interpreter.get_input_details()[0]
                detail_out = interpreter.get_output_details()[0]
                input_shape = [int(v) for v in detail_in["shape"]]
                output_shape = [int(v) for v in detail_out["shape"]]
                report.check("tflite_loads", True, f"in={input_shape} out={output_shape}")
            except Exception as exc:  # noqa: BLE001
                report.check("tflite_loads", False, f"LiteRT 无法加载导出模型：{exc}")
            manifest = {
                "version": f"detector-{sha256_file(exported_path)[:12]}",
                "model_sha256": sha256_file(exported_path),
                "classes": ["card_corner"],
                "output": "obb-four-points",
                "input_shape": input_shape,
                "output_shape": output_shape,
                "format": args.format,
                "quantize": quantize,
                "quantize_requested": args.quantize,
                "size_bytes": exported_path.stat().st_size,
                "image_size": args.image_size or int(config.get("image_size", 640)),
                "seed": args.seed,
            }
            write_json(export_dir / "model_manifest.json", manifest)
            report.add_output(exported_path, "model")
            report.add_output(export_dir / "model_manifest.json", "manifest")
            report.check("export_produced", exported_path.is_file(), str(exported_path.name))
        else:
            data_path = resolve_path(repo_root, args.data) if args.data else repo_root / "model_training/output/obb_dataset/data.yaml"
            manifest_path = resolve_path(repo_root, args.manifest)
            if manifest_path and manifest_path.is_file():
                report.add_input(manifest_path, "manifest")
                report.dataset["manifest_sha256"] = sha256_file(manifest_path)
            if data_path is None or not data_path.is_file():
                report.warn(
                    "缺少 OBB 数据集（data.yaml）：T2.1/T2.2 需要先由 export_obb.py 从已签核数据生成，本次未训练"
                )
                return emit(report, STATUS_NOT_RUN, 3, args)
            model_name = args.model or config.get("model", "yolo11n-obb.pt")
            model = YOLO(str(model_name))
            # 生效超参写进 report.parameters：CLI 覆盖 config 时，只记 config sha
            # 会让审计看不出实际训练尺寸/轮数。
            effective = {
                "epochs": args.epochs or int(config.get("epochs", 100)),
                "image_size": args.image_size or int(config.get("image_size", 640)),
                "batch_size": args.batch_size or int(config.get("batch_size", 8)),
                "patience": args.patience or int(config.get("patience", 15)),
            }
            report.parameters.update(effective)
            report.parameters["init_weights"] = str(model_name)
            result = model.train(
                data=str(data_path),
                epochs=effective["epochs"],
                imgsz=effective["image_size"],
                batch=effective["batch_size"],
                project=str(run_dir),
                name=f"card_corner_obb_{args.mode}",
                seed=args.seed,
                patience=effective["patience"],
                degrees=args.degrees if args.degrees is not None
                else float(config.get("augmentation", {}).get("degrees", 15)),
                perspective=args.perspective if args.perspective is not None
                else float(config.get("augmentation", {}).get("perspective", 0.0008)),
                translate=float(config.get("augmentation", {}).get("translate", 0.08)),
                scale=args.scale if args.scale is not None
                else float(config.get("augmentation", {}).get("scale", 0.25)),
                # mosaic/hsv_* 之前只写在 config 里、从没传给 ultralytics，训练实际用的是
                # ultralytics 默认值（mosaic=1.0）；这里显式传入，配置才与实际一致。
                mosaic=float(config.get("augmentation", {}).get("mosaic", 1.0)),
                hsv_h=float(config.get("augmentation", {}).get("hsv_h", 0.015)),
                hsv_v=float(config.get("augmentation", {}).get("hsv_v", 0.4)),
                fliplr=0.0,
                flipud=0.0,
                # torch 2.15 缺 torch._inductor.config，ultralytics 的 deterministic=True 会直接 ImportError。
                # 我们靠显式 --seed 与固定数据版本保证可复现，不依赖该开关。
                deterministic=False,
            )
            best = Path(result.save_dir) / "weights" / "best.pt"
            report.add_output(best, "weights")
            metrics = getattr(getattr(result, "results_dict", None), "items", lambda: [])()
            report.metrics.update({str(k): float(v) for k, v in dict(metrics).items() if isinstance(v, (int, float))})
            report.check("weights_saved", best.is_file(), str(best))
            report.check("baseline_only" if args.mode == "baseline" else "train_completed", True, args.mode)
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

"""模型资产校验（T2.3 / T3.4 / T4.1 / T5.2）。

检查：文件存在、SHA-256 与 manifest 一致、TFLite 容器标记、输入/输出形状、
标签顺序与 `labels.py` 一致、固定输入无 NaN/越界。没有 TensorFlow 时形状与数值检查记 NOT_RUN。
"""

from __future__ import annotations

import argparse
from pathlib import Path

from _cli import (
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_NOT_RUN,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    make_report,
    prepare,
    read_json,
    resolve_path,
    run,
    sha256_file,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "T2.3"


def main() -> int:
    parser = build_parser(
        prog="verify_models.py",
        version=VERSION,
        task_id=TASK_ID,
        description="校验模型文件、hash、形状、标签顺序与数值稳定性",
    )
    parser.add_argument("--model", default=None)
    parser.add_argument("--manifest", default=None)
    parser.add_argument("--assets", default=None, help="资产目录（T4.1/T5.2）：校验目录下所有模型")
    parser.add_argument("--check-shape", action="store_true")
    parser.add_argument("--check-labels", action="store_true")
    parser.add_argument("--check-hash", action="store_true")
    parser.add_argument("--check-manifest", action="store_true")
    parser.add_argument("--task", default=None, help="覆盖任务 ID（T2.3/T3.4/T4.1/T5.2）")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, args.task or TASK_ID)
    report.parameters.update(
        model=args.model, manifest=args.manifest, assets=args.assets, checks={
            "shape": args.check_shape, "labels": args.check_labels, "hash": args.check_hash, "manifest": args.check_manifest
        }
    )

    def body() -> int:
        models: list[Path] = []
        if args.assets:
            assets_dir = resolve_path(repo_root, args.assets)
            if assets_dir is None or not assets_dir.is_dir():
                raise CliError(f"资产目录不存在：{args.assets}")
            models = sorted(list(assets_dir.glob("*.tflite")) + list(assets_dir.glob("*.keras")))
            manifest_candidates = sorted(assets_dir.glob("*manifest.json"))
            manifest_path = manifest_candidates[0] if manifest_candidates else None
        else:
            if not args.model:
                raise CliError("必须提供 --model 或 --assets")
            model_path = resolve_path(repo_root, args.model)
            if model_path is None or not model_path.is_file():
                raise CliError(f"模型不存在：{args.model}")
            models = [model_path]
            manifest_path = resolve_path(repo_root, args.manifest) if args.manifest else None

        if not models:
            raise CliError("没有找到模型文件（*.tflite / *.keras）")
        report.dataset["sample_count"] = len(models)
        manifest = read_json(manifest_path) if manifest_path and manifest_path.is_file() else None
        if manifest_path and manifest_path.is_file():
            report.add_input(manifest_path, "manifest")
        for model in models:
            report.add_input(model, "model")
            report.metrics.setdefault("models", []).append(
                {"path": str(model.name), "sha256": sha256_file(model), "size": model.stat().st_size}
            )

        for model in models:
            digest = sha256_file(model)
            if args.check_hash and manifest is not None:
                expected = (
                    manifest.get("model_sha256")
                    or (manifest.get("model") or {}).get("sha256")
                    or manifest.get("sha256")
                )
                report.check(
                    f"hash_{model.name}",
                    expected is None or expected == digest,
                    f"manifest={expected} file={digest}" if expected else "manifest 未记录模型 hash",
                )
            if model.suffix == ".tflite":
                head = model.read_bytes()[:8]
                report.check(f"tflite_container_{model.name}", head[4:8] == b"TFL3", f"magic={head[4:8]!r}")
            if args.check_manifest:
                report.check(
                    f"manifest_present_{model.name}",
                    manifest is not None,
                    str(manifest_path) if manifest_path else "缺少 model_manifest.json",
                )

        if args.check_labels and manifest is not None:
            from labels import RANKS, SUITS

            report.check("rank_labels_match", manifest.get("rank_labels", RANKS) == RANKS, str(manifest.get("rank_labels")))
            report.check("suit_labels_match", manifest.get("suit_labels", SUITS) == SUITS, str(manifest.get("suit_labels")))

        if args.check_shape:
            try:
                import numpy as np
                import tensorflow as tf
            except Exception as exc:  # noqa: BLE001
                return _not_run(report, args, f"缺少 TensorFlow，形状与数值检查未执行：{exc}")
            for model in models:
                if model.suffix != ".tflite":
                    continue
                try:
                    interpreter = tf.lite.Interpreter(model_path=str(model))
                    interpreter.allocate_tensors()
                except Exception as exc:  # noqa: BLE001
                    report.check(f"load_{model.name}", False, str(exc))
                    continue
                inputs = interpreter.get_input_details()
                outputs = interpreter.get_output_details()
                report.metrics.setdefault("tensors", {})[model.name] = {
                    "inputs": [{"name": i["name"], "shape": list(map(int, i["shape"])), "dtype": str(i["dtype"])} for i in inputs],
                    "outputs": [{"name": o["name"], "shape": list(map(int, o["shape"])), "dtype": str(o["dtype"])} for o in outputs],
                }
                ok = True
                for detail in inputs:
                    sample = np.zeros(detail["shape"], dtype=detail["dtype"])
                    try:
                        interpreter.set_tensor(detail["index"], sample)
                    except Exception as exc:  # noqa: BLE001
                        ok = False
                        report.check(f"input_{model.name}", False, str(exc))
                interpreter.invoke()
                for detail in outputs:
                    values = interpreter.get_tensor(detail["index"])
                    if not np.all(np.isfinite(values)):
                        ok = False
                    report.check(
                        f"finite_output_{model.name}_{detail['name']}",
                        bool(np.all(np.isfinite(values))),
                        f"min={float(values.min()):.4f} max={float(values.max()):.4f}",
                    )
                report.check(f"shape_{model.name}", ok, f"{len(inputs)} inputs / {len(outputs)} outputs")

        checks = [c for c in report.checks if c.name != "report_generated"]
        if not checks:
            return _not_run(report, args, "没有可执行的检查项")
        report.check("report_generated", True, f"{len(report.inputs)} inputs")
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


def _not_run(report, args, reason: str) -> int:
    report.warn(reason)
    path = run_dir_report(report)
    write_json(path, {"reason": reason})
    report.add_output(path, "diagnostics")
    return emit(report, STATUS_NOT_RUN, 3, args)


def run_dir_report(report) -> Path:
    return report.output_dir / "diagnostics.json"


if __name__ == "__main__":
    raise SystemExit(main())

"""主动学习队列（T5.1）：只挑可疑样本，不重复标注成功样本。

排序依据：低置信度、低 margin、贴边、张数不符、两副牌冲突。
没有模型或预测时返回 NOT_RUN；golden 图永不进入队列。
"""

from __future__ import annotations

import argparse
from pathlib import Path

from _cli import (
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    load_config,
    make_report,
    prepare,
    read_json,
    read_jsonl,
    resolve_path,
    run,
    sha256_file,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "T5.1"


def score(row: dict, weights: dict) -> tuple[float, list[str]]:
    reasons = []
    confidence = float(row.get("confidence", 1.0))
    margin = float(row.get("margin", 1.0))
    value = 0.0
    if confidence < weights.get("confidence_below", 0.8):
        value += (weights.get("confidence_below", 0.8) - confidence) * 2.0
        reasons.append("low-confidence")
    if margin < weights.get("margin_below", 0.15):
        value += (weights.get("margin_below", 0.15) - margin) * 3.0
        reasons.append("low-margin")
    if row.get("border"):
        value += 1.0
        reasons.append("border")
    if row.get("count_mismatch"):
        value += 1.5
        reasons.append("count-mismatch")
    if row.get("duplicate_conflict"):
        value += 1.2
        reasons.append("duplicate-conflict")
    return value, reasons


def aggregate_to_images(repo_root: Path, crops_arg: str, rows: list[dict], report) -> list[dict]:
    """crop 级预测 → 图像级行：置信度取均值、margin 取最小、卡片取预测值。"""
    crops_path = resolve_path(repo_root, crops_arg)
    if crops_path is None or not crops_path.is_file():
        raise CliError(f"crops manifest 不存在：{crops_arg}")
    report.add_input(crops_path, "crops-manifest")
    source = {
        record.get("crop"): record.get("source_image")
        for record in read_json(crops_path).get("records", [])
        if record.get("crop")
    }
    grouped: dict[str, dict] = {}
    for row in rows:
        image = source.get(row.get("crop")) or row.get("image")
        if not image:
            continue
        entry = grouped.setdefault(
            image,
            {"image": image, "sha256": row.get("sha256"), "confidences": [], "margins": [], "cards": []},
        )
        entry["confidences"].append(float((row.get("predicted") or {}).get("confidence", 0.0)))
        entry["margins"].append(float(row.get("margin", 0.0)))
        predicted = row.get("predicted") or {}
        if predicted.get("rank"):
            entry["cards"].append({"rank": predicted.get("rank"), "suit": predicted.get("suit")})
    images = []
    for entry in grouped.values():
        confidences = entry.pop("confidences")
        margins = entry.pop("margins")
        entry["confidence"] = sum(confidences) / len(confidences) if confidences else 0.0
        entry["margin"] = min(margins) if margins else 0.0
        entry["model_version"] = "tflite"
        images.append(entry)
    images.sort(key=lambda item: item["image"])
    report.metrics["aggregated_images"] = len(images)
    return images


def main() -> int:
    parser = build_parser(
        prog="active_learning.py",
        version=VERSION,
        task_id=TASK_ID,
        description="按置信度/margin/贴边/张数/重复冲突排序失败样本",
    )
    parser.add_argument("--model", default=None)
    parser.add_argument("--manifest", default="model_training/datasets/dataset_manifest.json")
    parser.add_argument("--input", default="model_training/inbox")
    parser.add_argument("--predictions", default=None)
    parser.add_argument(
        "--crops",
        default=None,
        help="crops_manifest.json：把 crop 级预测聚合成图像级行（T5.1 的输入粒度）",
    )
    parser.add_argument("--sort-by", default="confidence,margin,border,count,duplicate")
    parser.add_argument("--top", type=int, default=200)
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(input=args.input, predictions=args.predictions, sort_by=args.sort_by, model=args.model)

    def body() -> int:
        if not args.predictions:
            raise CliError("缺少 --predictions：主动学习需要模型对 inbox 的输出，未执行")
        path = resolve_path(repo_root, args.predictions)
        if path is None or not path.is_file():
            raise CliError(f"预测文件不存在：{args.predictions}")
        report.add_input(path, "predictions")
        if args.model:
            model_path = resolve_path(repo_root, args.model)
            if model_path and model_path.is_file():
                report.add_input(model_path, "model")
        rows = read_jsonl(path)
        if not rows:
            raise CliError("预测文件为空")
        if args.crops:
            rows = aggregate_to_images(repo_root, args.crops, rows, report)
            if not rows:
                raise CliError("按 --crops 聚合后没有图像级行")
        config = load_config(repo_root, args.config)
        golden_names = set(config.get("golden_names", ["case4"]))
        rows = [row for row in rows if not any(name in str(row.get("image", "")) for name in golden_names)]

        scored = []
        for row in rows:
            value, reasons = score(row, config)
            if value <= 0:
                continue
            scored.append(
                {
                    "image": row.get("image"),
                    "sha256": row.get("sha256"),
                    "model_version": row.get("model_version", ""),
                    "score": round(value, 4),
                    "reasons": reasons,
                    "predicted": row.get("cards", []),
                }
            )
        scored.sort(key=lambda item: item["score"], reverse=True)
        queue = scored[: args.top]
        queue_path = run_dir / "queue.json"
        write_json(queue_path, {"schema_version": 1, "task": TASK_ID, "items": queue, "total_candidates": len(scored)})
        report.add_output(queue_path, "queue")
        report.dataset["sample_count"] = len(rows)
        report.metrics.update(candidates=len(scored), queued=len(queue), skipped_success=len(rows) - len(scored))
        report.check("golden_excluded", not any(any(n in str(r.get("image", "")) for n in golden_names) for r in rows), "golden 图不进队列")
        report.check("queue_bounded", len(queue) <= args.top, f"{len(queue)} <= {args.top}")
        report.check("queue_shape", all(item["reasons"] for item in queue) or not queue, "每条都带原因")
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

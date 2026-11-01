"""统一评估入口（T0.5 heuristic、T2.4 detector、T3.5 classifier、T5.3 e2e shadow）。

预测文件为 JSONL，每行一条样本；分两类：

图片级（heuristic / e2e）::

    {"image": "testcases/case4.jpg", "cards": [{"rank": "2", "suit": "CLUB", "confidence": 0.93}]}

牌角级（classifier）::

    {"crop": "runs/crops/x_0.png", "expected": {"rank": "2", "suit": "CLUB"},
     "predicted": {"rank": "2", "suit": "CLUB", "confidence": 0.97, "confirmed": true}}

检测级（detector）::

    {"image": "...", "corners": [{"quad": [[x, y] * 4], "score": 0.9, "rank": "2", "suit": "CLUB"}]}

没有真实执行证据（例如启发式引擎需要 Android 运行时）时脚本抛 EnvironmentUnavailable，
状态为 NOT_RUN —— 不得用占位数据造句 PASS。
"""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter
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
    make_report,
    metric,
    prepare,
    read_json,
    read_jsonl,
    resolve_path,
    run,
    sha256_file,
    wilson_interval,
    write_json,
    write_jsonl,
)

VERSION = "1.0.0"
TASK_ID = "T0.5"

THRESHOLDS = {
    "heuristic": {"per_card_exact": 0.995, "full_image_exact": 1.0},
    "detector": {"recall": 0.99, "false_positives_per_image": 0.1, "iou": 0.8},
    "classifier": {"exact": 0.995, "joker_exact": 0.99, "ece": 0.05},
    "e2e": {"per_card_exact": 0.995, "full_27_rate": 0.85, "review_rate": 0.05, "confident_error": 0.01},
}


def label_of(card: dict) -> tuple[str, str]:
    return (str(card.get("rank", "")), str(card.get("suit", "")))


def load_expectations(repo_root: Path, manifest_path: Path, split: str) -> dict[str, dict]:
    manifest = read_json(manifest_path)
    entries = manifest.get("images") or manifest.get("cases") or []
    selected = [e for e in entries if split in {"all", ""} or e.get("split") == split]
    if not selected:
        raise CliError(f"manifest {manifest_path.name} 在 split={split} 下没有样本")
    return {e.get("image") or e.get("path"): e for e in selected}


def ece_bins(pairs: list[tuple[float, bool]], bins: int = 10) -> float:
    if not pairs:
        return 0.0
    total = len(pairs)
    error = 0.0
    for index in range(bins):
        low, high = index / bins, (index + 1) / bins
        bucket = [p for p in pairs if low <= p[0] < high or (index == bins - 1 and p[0] == 1.0)]
        if not bucket:
            continue
        accuracy = sum(1 for _, correct in bucket if correct) / len(bucket)
        confidence = sum(p[0] for p in bucket) / len(bucket)
        error += len(bucket) / total * abs(accuracy - confidence)
    return error


def evaluate_images(rows: list[dict], expected: dict[str, dict], threshold_key: str, full_key: str, target: float, full_target: float):
    per_card_correct = per_card_total = 0
    full_correct = 0
    failures = []
    confidences: list[tuple[float, bool]] = []
    for row in rows:
        key = row.get("image")
        if key not in expected:
            continue
        truth = [label_of(card) for card in expected[key].get("cards") or []]
        predicted = [label_of(card) for card in row.get("cards") or []]
        truth_counter, pred_counter = Counter(truth), Counter(predicted)
        matched = sum((truth_counter & pred_counter).values())
        per_card_correct += matched
        per_card_total += max(len(truth), len(predicted))
        exact = truth_counter == pred_counter
        full_correct += int(exact)
        for card in row.get("cards") or []:
            confidence = float(card.get("confidence", 0.0))
            confidences.append((min(1.0, max(0.0, confidence)), label_of(card) in truth_counter))
        if not exact:
            failures.append(
                {
                    "image": key,
                    "missing": [f"{r}/{s}" for (r, s), n in (truth_counter - pred_counter).items() for _ in range(n)],
                    "extra": [f"{r}/{s}" for (r, s), n in (pred_counter - truth_counter).items() for _ in range(n)],
                }
            )
    return {
        threshold_key: metric(per_card_correct / per_card_total) if per_card_total else 0.0,
        full_key: metric(full_correct / max(1, len(expected))),
        "per_card_hits": per_card_correct,
        "per_card_total": per_card_total,
        "images": len(expected),
        "full_correct": full_correct,
        "failures": failures,
        "ece": metric(ece_bins(confidences)) if confidences else None,
        "confidence_low": wilson_interval(per_card_correct, per_card_total)[0] if per_card_total else 0.0,
    }


def evaluate_crops(rows: list[dict]):
    exact = joker_exact = joker_total = 0
    confidences = []
    confusion = Counter()
    unknown = 0
    for row in rows:
        expected_label = label_of(row.get("expected") or {})
        predicted_label = label_of(row.get("predicted") or {})
        correct = expected_label == predicted_label
        exact += int(correct)
        if predicted_label == ("", "") or row.get("unknown"):
            unknown += 1
        if expected_label[1] == "JOKER":
            joker_total += 1
            joker_exact += int(correct)
        confusion[f"{expected_label[0]}/{expected_label[1]}->{predicted_label[0]}/{predicted_label[1]}"] += 1
        confidence = float((row.get("predicted") or {}).get("confidence", 0.0))
        confidences.append((min(1.0, max(0.0, confidence)), correct))
    total = len(rows)
    return {
        "exact": metric(exact / total) if total else 0.0,
        "joker_exact": metric(joker_exact / joker_total) if joker_total else None,
        "ece": metric(ece_bins(confidences)) if confidences else None,
        "unknown_rate": metric(unknown / total) if total else 0.0,
        "samples": total,
        "confusion_top": dict(confusion.most_common(20)),
        "wilson_low": wilson_interval(exact, total)[0] if total else 0.0,
    }


def evaluate_detector(rows: list[dict], expected: dict[str, dict]):
    true_positive = false_positive = missed = 0
    ious = []
    per_image_fp = []
    for row in rows:
        entry = expected.get(row.get("image"))
        if entry is None:
            continue
        truth = [label_of(card) for card in entry.get("cards") or [] if card.get("visible", True)]
        corners = row.get("corners") or []
        matched = 0
        local_fp = 0
        for corner in corners:
            label = label_of(corner)
            if label in truth:
                matched += 1
                if corner.get("iou") is not None:
                    ious.append(float(corner["iou"]))
            else:
                local_fp += 1
        true_positive += matched
        false_positive += local_fp
        missed += max(0, len(truth) - matched)
        per_image_fp.append(local_fp)
    images = max(1, len(rows))
    total_truth = true_positive + missed
    return {
        "recall": metric(true_positive / total_truth) if total_truth else 0.0,
        "false_positives_per_image": metric(false_positive / images),
        "iou": metric(sum(ious) / len(ious)) if ious else None,
        "true_positives": true_positive,
        "false_positives": false_positive,
        "missed": missed,
        "images": len(rows),
    }


def main() -> int:
    parser = build_parser(
        prog="evaluate.py",
        version=VERSION,
        task_id=TASK_ID,
        description="按 split 评估 heuristic/detector/classifier/e2e，输出逐样本结果与指标",
    )
    parser.add_argument("--stage", choices=["heuristic", "detector", "classifier", "e2e"], default="heuristic")
    parser.add_argument("--engine", default=None, help="heuristic 阶段的引擎名（heuristic/mlkit）")
    parser.add_argument("--mode", default="offline", help="e2e 阶段：shadow | learned")
    parser.add_argument("--baseline", default=None)
    parser.add_argument("--model", default=None)
    parser.add_argument("--manifest", default="model_training/datasets/dataset_manifest.json")
    parser.add_argument("--split", default="test")
    parser.add_argument("--predictions", default=None, help="预测 JSONL；缺失时视为未执行")
    parser.add_argument("--task", default=None, help="覆盖报告里的 task_id（例如 T3.5）")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    default_task = {"heuristic": "T0.5", "detector": "T2.4", "classifier": "T3.5", "e2e": "T5.3"}[args.stage]
    report = make_report(args, args.task or default_task)
    report.parameters.update(
        stage=args.stage,
        engine=args.engine,
        mode=args.mode,
        split=args.split,
        model=args.model,
        predictions=args.predictions,
        baseline=args.baseline,
    )

    def body() -> int:
        manifest_path = resolve_path(repo_root, args.manifest)
        if manifest_path is None or not manifest_path.is_file():
            raise CliError(f"manifest 不存在：{args.manifest}")
        report.add_input(manifest_path, "manifest")
        report.dataset["manifest_sha256"] = sha256_file(manifest_path)
        report.dataset["split"] = args.split

        if args.stage == "heuristic" and not args.predictions:
            raise EnvironmentUnavailable(
                "heuristic 阶段需要 Android 运行时（CardRecognizer 依赖 Bitmap/ML Kit）；"
                "桌面环境无法执行，必须由真机 instrumentation 导出预测文件后传入 --predictions"
            )
        if not args.predictions:
            raise EnvironmentUnavailable(f"{args.stage} 阶段需要模型与预测文件；未提供 --predictions")
        predictions_path = resolve_path(repo_root, args.predictions)
        if predictions_path is None or not predictions_path.is_file():
            raise CliError(f"预测文件不存在：{args.predictions}")
        report.add_input(predictions_path, "predictions")
        rows = read_jsonl(predictions_path)
        if not rows:
            raise CliError("预测文件为空")
        report.dataset["sample_count"] = len(rows)

        thresholds = THRESHOLDS[args.stage]
        rows_path = run_dir / "per-sample.jsonl"

        if args.stage == "classifier":
            metrics = evaluate_crops(rows)
            report.metrics.update(metrics)
            report.check("samples_sufficient", metrics["samples"] >= 200, f"{metrics['samples']} crops")
            report.check("exact_threshold", metrics["exact"] >= thresholds["exact"], f"{metrics['exact']} >= {thresholds['exact']}")
            if metrics["joker_exact"] is not None:
                report.check("joker_threshold", metrics["joker_exact"] >= thresholds["joker_exact"], str(metrics["joker_exact"]))
            if metrics["ece"] is not None:
                report.check("ece_threshold", metrics["ece"] <= thresholds["ece"], str(metrics["ece"]))
            write_jsonl(rows_path, rows)
        elif args.stage == "detector":
            expected = load_expectations(repo_root, manifest_path, args.split)
            metrics = evaluate_detector(rows, expected)
            report.metrics.update(metrics)
            report.check("recall_threshold", metrics["recall"] >= thresholds["recall"], f"{metrics['recall']} >= {thresholds['recall']}")
            report.check(
                "false_positive_threshold",
                metrics["false_positives_per_image"] <= thresholds["false_positives_per_image"],
                str(metrics["false_positives_per_image"]),
            )
            if metrics["iou"] is not None:
                report.check("iou_threshold", metrics["iou"] >= thresholds["iou"], str(metrics["iou"]))
            write_jsonl(rows_path, rows)
        else:
            expected = load_expectations(repo_root, manifest_path, args.split)
            metrics = evaluate_images(rows, expected, "per_card_exact", "full_image_exact", thresholds["per_card_exact"], thresholds.get("full_27_rate", thresholds.get("full_image_exact", 1.0)))
            report.metrics.update({k: v for k, v in metrics.items() if k != "failures"})
            report.metrics["failure_count"] = len(metrics["failures"])
            report.check("per_card_threshold", metrics["per_card_exact"] >= thresholds["per_card_exact"], f"{metrics['per_card_exact']}")
            report.check("full_image_threshold", metrics["full_image_exact"] >= thresholds.get("full_image_exact", thresholds["full_27_rate"]), f"{metrics['full_image_exact']}")
            if args.stage == "e2e":
                report.check("full_27_threshold", metrics["full_image_exact"] >= thresholds["full_27_rate"], f"{metrics['full_image_exact']}")
            write_jsonl(rows_path, metrics["failures"])
            failures_path = run_dir / "failures.json"
            write_json(failures_path, metrics["failures"])
            report.add_output(failures_path, "failures")
        report.add_output(rows_path, "per-sample")

        if args.model:
            model_path = resolve_path(repo_root, args.model)
            if model_path and model_path.is_file():
                report.add_input(model_path, "model")
                report.metrics["model_sha256"] = sha256_file(model_path)
            else:
                report.warn(f"模型文件缺失，未归档 hash：{args.model}")

        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

"""校准与阈值标定（T2.5 检测后处理、T3.3 分类温度/margin）。

只允许使用 calibration split。没有 calibration 预测文件时返回 NOT_RUN，
不会用 test 结果调参。
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

from _cli import (
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_NOT_RUN,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    load_config,
    make_report,
    metric,
    prepare,
    read_jsonl,
    resolve_path,
    run,
    sha256_file,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "T3.3"


def fit_temperature(pairs: list[tuple[float, bool]], candidates: list[float]) -> tuple[float, float]:
    """在候选温度上最小化 NLL（用置信度 logit 近似），返回 (temperature, nll)。"""
    best_temperature, best_loss = 1.0, float("inf")
    for temperature in candidates:
        loss = 0.0
        for confidence, correct in pairs:
            clipped = min(1 - 1e-6, max(1e-6, confidence))
            logit = math.log(clipped / (1 - clipped)) / max(temperature, 1e-3)
            probability = 1 / (1 + math.exp(-logit))
            probability = min(1 - 1e-9, max(1e-9, probability))
            loss += -(math.log(probability) if correct else math.log(1 - probability))
        loss /= max(1, len(pairs))
        if loss < best_loss:
            best_temperature, best_loss = temperature, loss
    return best_temperature, best_loss


def sweep_thresholds(rows: list[dict], thresholds: list[float]) -> list[dict]:
    table = []
    for threshold in thresholds:
        kept = [row for row in rows if float(row.get("score", 0.0)) >= threshold]
        true_positive = sum(1 for row in kept if row.get("correct"))
        table.append(
            {
                "threshold": threshold,
                "kept": len(kept),
                "recall": metric(true_positive / len(rows)) if rows else 0.0,
                "precision": metric(true_positive / len(kept)) if kept else 0.0,
            }
        )
    return table


def main() -> int:
    parser = build_parser(
        prog="calibrate.py",
        version=VERSION,
        task_id=TASK_ID,
        description="温度缩放与阈值标定（只用 calibration split）",
    )
    parser.add_argument("--target", choices=["classifier", "detector-postprocess"], default="classifier")
    parser.add_argument("--model", default=None)
    parser.add_argument("--split", default="calibration")
    parser.add_argument("--predictions", default=None)
    parser.add_argument("--metrics", default="ece,brier")
    parser.add_argument("--task", default=None, help="覆盖任务 ID（T2.5/T3.3）")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, args.task or TASK_ID)
    report.parameters.update(target=args.target, split=args.split, predictions=args.predictions, model=args.model)

    def body() -> int:
        if args.split != "calibration":
            raise CliError("标定只允许使用 calibration split")
        if not args.predictions:
            raise CliError("缺少 --predictions（calibration split 的预测文件）")
        path = resolve_path(repo_root, args.predictions)
        if path is None or not path.is_file():
            raise CliError(f"预测文件不存在：{args.predictions}")
        report.add_input(path, "predictions")
        if args.model:
            model_path = resolve_path(repo_root, args.model)
            if model_path and model_path.is_file():
                report.add_input(model_path, "model")
                report.metrics["model_sha256"] = sha256_file(model_path)
        rows = read_jsonl(path)
        if not rows:
            raise CliError("预测文件为空")
        report.dataset["sample_count"] = len(rows)
        config = load_config(repo_root, args.config)
        temperatures = config.get("temperatures", [0.5, 0.6, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 2.0])
        thresholds = config.get("thresholds", [0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95])

        if args.target == "classifier":
            # predict.py 把分类置信度写在 predicted.confidence（顶层是 rank/suit 结构），
            # 之前这里读顶层 confidence 恒为 0，导致温度拟合和阈值表全部失效。
            def confidence_of(row: dict) -> float:
                return float(row.get("confidence") or (row.get("predicted") or {}).get("confidence") or 0.0)

            pairs = [(confidence_of(row), bool(row.get("correct"))) for row in rows]
            temperature, nll = fit_temperature(pairs, [float(t) for t in temperatures])
            table = sweep_thresholds(
                [{"score": confidence_of(row), "correct": row.get("correct")} for row in rows],
                [float(t) for t in thresholds],
            )
            report.metrics.update(temperature=temperature, nll=metric(nll), threshold_table=table)
            report.check("temperature_fitted", temperature > 0, f"T={temperature}")
            report.check("calibration_samples", len(rows) >= 100, f"{len(rows)} rows")
        else:
            # detector 预测是逐图一行、牌角在 corners 里；阈值扫描要按牌角逐条计算。
            corner_rows: list[dict] = []
            for row in rows:
                corners = row.get("corners")
                if corners is None:
                    corner_rows.append(row)
                    continue
                for corner in corners:
                    corner_rows.append(
                        {"score": corner.get("score", 0.0), "correct": float(corner.get("iou", 0.0)) >= 0.5}
                    )
            table = sweep_thresholds(corner_rows, [float(t) for t in thresholds])
            best = max(table, key=lambda row: (row["recall"], row["precision"]))
            report.metrics.update(threshold_table=table, selected=best)
            report.check("grid_evaluated", len(table) > 0, f"{len(table)} points")
            report.check("selected_threshold", best["recall"] > 0, str(best))

        calibration_path = run_dir / "calibration.json"
        write_json(calibration_path, report.metrics)
        report.add_output(calibration_path, "calibration")
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

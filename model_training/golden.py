"""生成 golden 回归集（T0.5）。

把 `model_training/datasets/golden_truth.json`（转写自 doc/ai_baseline.md 的人工牌面）
与 testcases 里的真实图片绑定：图片 SHA-256、scene_id、可见牌角清单、完全遮挡项。
首次运行时把图片 hash 冻结进真值文件；此后 hash 不一致即 FAIL。

人工签核见 model_training/approvals/golden-definition.json；本脚本不代签，
缺签核时状态为 BLOCKED，但仍会写出 manifest 作为证据。
"""

from __future__ import annotations

import argparse

from _cli import (
    EXIT_APPROVAL,
    EXIT_FAIL,
    EXIT_PASS,
    read_json,
    STATUS_BLOCKED,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    load_approvals,
    make_report,
    prepare,
    rel,
    resolve_path,
    run,
    sha256_file,
    sha256_json,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "T0.5"


def main() -> int:
    parser = build_parser(
        prog="golden.py",
        version=VERSION,
        task_id=TASK_ID,
        description="把人工确认的 golden 牌面与真实图片绑定，生成 golden_manifest.json",
    )
    parser.add_argument("command", choices=["prepare"])
    parser.add_argument(
        "--cases",
        default="case4",
        help="逗号分隔的 case 名；传 all 表示 golden_truth.json 里登记的全部图片（含 testcases 全量）",
    )
    parser.add_argument("--truth", default="model_training/datasets/golden_truth.json")
    parser.add_argument("--output", default=None, help="manifest 输出路径；默认 <run-dir>/golden_manifest.json")
    parser.add_argument("--no-freeze", action="store_true", help="不把图片 hash 回填真值文件")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(cases=args.cases, truth=args.truth, freeze=not args.no_freeze)

    def body() -> int:
        truth_path = resolve_path(repo_root, args.truth)
        if truth_path is None or not truth_path.is_file():
            raise CliError(f"golden 真值文件不存在：{args.truth}")
        truth = read_json(truth_path)
        report.add_input(truth_path, "truth")
        if args.cases.strip().lower() == "all":
            wanted = [case.get("name") for case in truth.get("cases", [])]
        else:
            wanted = [name.strip() for name in args.cases.split(",") if name.strip()]
        by_name = {case["name"]: case for case in truth.get("cases", [])}

        entries = []
        undetermined: list[str] = []
        frozen = False
        for name in wanted:
            case = by_name.get(name)
            if case is None:
                raise CliError(f"真值文件缺少 case：{name}")
            image_path = resolve_path(repo_root, case["path"])
            if image_path is None or not image_path.is_file():
                raise CliError(f"图片不存在：{case['path']}")
            digest = sha256_file(image_path)
            if case.get("sha256") is None:
                if not args.no_freeze:
                    case["sha256"] = digest
                    frozen = True
                report.warn(f"{name} 的图片 hash 之前未冻结，本次记录为 {digest}")
            elif case["sha256"] != digest:
                report.check(f"hash_match_{name}", False, f"{digest} != {case['sha256']}")
                continue
            report.check(f"hash_match_{name}", True, digest)
            cards = case.get("cards") or []
            if not cards:
                # 已登记进验证集、hash 已冻结，但没有人工真值：不能算通过，也不能自动编牌。
                undetermined.append(name)
                report.check(f"truth_registered_{name}", True, "已登记到验证集，等待人工确认牌面")
            else:
                report.check(
                    f"cards_{name}",
                    all(0 < len(c.get("rank", "")) and 0 < len(c.get("suit", "")) for c in cards),
                    f"{len(cards)} cards",
                )
            entries.append(
                {
                    "image": case["path"],
                    "sha256": digest,
                    "scene_id": case.get("scene_id", f"golden-{name}"),
                    "split": "golden",
                    "role": case.get("role", "auxiliary"),
                    "require_full_accuracy": bool(case.get("require_full_accuracy")),
                    "visible_exact_threshold": case.get("visible_exact_threshold"),
                    "fully_occluded": case.get("fully_occluded", []),
                    "pending_human_items": case.get("pending_human_items", []),
                    "cards": cards,
                }
            )
            report.add_input(image_path, "image")

        if frozen:
            truth_path.write_text(
                __import__("json").dumps(truth, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            report.add_output(truth_path, "truth-frozen")

        manifest = {
            "schema_version": 1,
            "dataset": "golden",
            "version": f"golden-{sha256_json(entries)[:12]}",
            "generated_at": report.started_at,
            "source": "doc/ai_baseline.md §2",
            "notes": [
                "golden 只用于固定回归，不得参与训练、校准或反复调参。",
                "真值与可见性由人工确认；脚本只做 hash 绑定与格式校验。",
            ],
            "images": entries,
        }
        manifest_path = resolve_path(repo_root, args.output) if args.output else (run_dir / "golden_manifest.json")
        assert manifest_path is not None
        write_json(manifest_path, manifest)
        report.add_output(manifest_path, "manifest")
        report.dataset = {
            "version": manifest["version"],
            "manifest_sha256": sha256_file(manifest_path),
            "split": "golden",
            "sample_count": len(entries),
        }
        report.metrics["golden_images"] = len(entries)
        report.metrics["golden_cards"] = sum(len(e["cards"]) for e in entries)

        report.approvals = load_approvals(repo_root, ["golden-definition"])
        # 签核存在不等于内容有效：必须与真值逐张一致，否则就是“签了一份旧的”。
        signed = None
        for approval in report.approvals:
            approval_path = resolve_path(repo_root, approval["path"])
            if approval.get("valid") and approval_path is not None and approval_path.is_file():
                signed = read_json(approval_path)
        if signed is not None:
            signed_images = {img.get("path"): img for img in signed.get("images", [])}
            for entry in entries:
                if not entry["cards"]:
                    continue
                name = f"signed_match_{entry['scene_id']}"
                image = signed_images.get(entry["image"])
                if image is None:
                    report.check(name, False, "签核记录缺少该图片")
                    continue
                if image.get("sha256") and image["sha256"] != entry["sha256"]:
                    report.check(name, False, f"签核 hash {image['sha256']} 与图片 hash {entry['sha256']} 不一致")
                    continue
                detail = image.get("cards_detail")
                if not detail:
                    report.check(name, True, "签核未逐张列出牌面（hash 已绑定）")
                    continue
                truth_cards = sorted((c["rank"], c["suit"]) for c in entry["cards"])
                signed_cards = sorted((c.get("rank"), c.get("suit")) for c in detail)
                report.check(
                    name,
                    truth_cards == signed_cards,
                    "签核牌面与 golden_truth.json 一致"
                    if truth_cards == signed_cards
                    else f"不一致：真值 {len(truth_cards)} 张 / 签核 {len(signed_cards)} 张；差异 {sorted(set(truth_cards) ^ set(signed_cards))}",
                )
        pending = [item for entry in entries for item in entry["pending_human_items"]]
        if pending:
            report.warn(f"{len(pending)} 项待人工确认：" + "；".join(pending))
        report.metrics["registered_images"] = len(entries)
        report.metrics["unsigned_images"] = len(undetermined)
        status = report.decide_status()
        if undetermined:
            status = STATUS_BLOCKED
            report.error(
                f"{len(undetermined)} 张图片已加入验证集但缺人工真值："
                + "、".join(undetermined)
                + "。请在 model_training/datasets/golden_truth.json 填 cards 并签核后重跑。"
            )
        if status == STATUS_BLOCKED:
            report.error(
                "golden 真值未签核：需要人工填写 model_training/approvals/golden-definition.json"
                "（images/reviewer/reviewed_at/version，并绑定上面记录的图片 hash）"
            )
            return emit(report, status, EXIT_APPROVAL, args)
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

"""登记并（可选）下载公开数据（T1.1）。

默认只做登记与本地校验，不联网；`--allow-network` 才真正下载。登记里带 `hf` 块的来源
按 Hugging Face 的 resolve 接口下载标注文件与限定数量的图片（`--limit-images`）。

许可字段只做登记，不影响数据能否加入训练（2026-11-02 起移除 dataset-license 门禁）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import threading
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from _cli import (
    EXIT_APPROVAL,
    EXIT_FAIL,
    EXIT_PASS,
    STATUS_BLOCKED,
    STATUS_PASS,
    CliError,
    build_parser,
    emit,
    make_report,
    prepare,
    read_json,
    rel,
    resolve_path,
    run,
    sha256_file,
    write_json,
)

VERSION = "1.1.0"
TASK_ID = "T1.1"

HF_BASE = "https://huggingface.co/datasets/{repo}/resolve/{revision}/{path}"
_REPORT_LOCK = threading.Lock()


def fetch(url: str, target: Path, max_bytes: int) -> dict:
    """下载单个文件并返回记录；超限或失败抛 ValueError。"""
    request = urllib.request.Request(url, headers={"User-Agent": "guandan-model-training/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = response.read(max_bytes + 1)
    if len(payload) > max_bytes:
        raise ValueError(f"超过单文件上限 {max_bytes} 字节")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(payload)
    return {"path": str(target), "sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload)}


def download_hf(source: dict, raw_dir: Path, max_bytes: int, limit_images: int, repo_root: Path, report) -> tuple[list[dict], list[dict], list[dict]]:
    """下载 hf 块的标注与图片子集，返回 (downloaded, skipped, image_ids)。"""
    spec = source["hf"]
    repo, revision = spec["repo"], spec.get("revision", "main")
    base = HF_BASE.format(repo=repo, revision=revision, path="{path}")
    downloaded, skipped = [], []

    def grab(remote_path: str) -> Path | None:
        url = base.format(path=remote_path)
        target = raw_dir / remote_path
        try:
            record = fetch(url, target, max_bytes)
        except Exception as exc:  # noqa: BLE001 - 网络失败进报告
            skipped.append({"id": source["id"], "file": remote_path, "reason": f"下载失败：{exc}"})
            return None
        record["path"] = rel(repo_root, target)
        downloaded.append(record)
        with _REPORT_LOCK:
            report.add_output(target, "download")
        return target

    annotations = []
    for remote in spec.get("annotations", []):
        path = grab(remote)
        if path is not None:
            annotations.append(path)
    if spec.get("classes"):
        grab(spec["classes"])

    image_ids = []
    if annotations and spec.get("images") and limit_images > 0:
        payload = json.loads(annotations[0].read_text(encoding="utf-8"))
        keys_all = sorted(payload.keys(), key=lambda k: int(k) if str(k).isdigit() else 0)
        if limit_images and len(keys_all) > limit_images:
            # 按步长均匀取样，避免“前 N 个 id”只覆盖一两个类别。
            step = len(keys_all) / limit_images
            keys = [keys_all[int(i * step)] for i in range(limit_images)]
        else:
            keys = keys_all[:limit_images] if limit_images > 0 else []
        workers = max(1, int(os.environ.get("GUANDAN_FETCH_WORKERS", "8")))
        with ThreadPoolExecutor(max_workers=workers) as pool:
            results = list(pool.map(lambda key: (key, grab(f"{spec['images']}/{key}.png")), keys))
        image_ids = [key for key, path in results if path is not None]
        report.metrics["fetch_workers"] = workers
    elif limit_images <= 0:
        skipped.append({"id": source["id"], "reason": "--limit-images=0，只下载标注"})
    return downloaded, skipped, image_ids


def main() -> int:
    parser = build_parser(
        prog="fetch_public_data.py",
        version=VERSION,
        task_id=TASK_ID,
        description="登记公开数据来源、许可与用途；可选下载并校验 hash",
    )
    parser.add_argument("--sources", default="model_training/datasets/sources.json")
    parser.add_argument("--allow-network", action="store_true", help="允许真实下载（默认不联网）")
    parser.add_argument("--max-bytes", type=int, default=20 * 1024 * 1024, help="单文件上限")
    parser.add_argument("--limit-images", type=int, default=200, help="每个 hf 来源最多下载的图片数（0 表示只下标注）")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(
        sources=args.sources,
        allow_network=args.allow_network,
        max_bytes=args.max_bytes,
        limit_images=args.limit_images,
    )

    def body() -> int:
        sources_path = resolve_path(repo_root, args.sources)
        if sources_path is None or not sources_path.is_file():
            raise CliError(f"来源登记不存在：{args.sources}")
        report.add_input(sources_path, "sources")
        sources = read_json(sources_path).get("sources", [])

        raw_dir = run_dir / "raw"
        raw_dir.mkdir(parents=True, exist_ok=True)
        records, missing_license, train_eligible, skipped = [], [], [], []
        for source in sources:
            license_name = str(source.get("license", "")).lower()
            record = {
                "id": source.get("id"),
                "kind": source.get("kind"),
                "url": source.get("url"),
                "version": source.get("version"),
                "license": license_name or "unknown",
                "annotation_kind": source.get("annotation_kind"),
                "requires_credentials": bool(source.get("requires_credentials")),
                "status": source.get("status"),
                "train_eligible": False,
                "downloaded": [],
            }
            if not license_name:
                missing_license.append(source.get("id"))
            record["train_eligible"] = True
            train_eligible.append(source.get("id"))
            if source.get("status") == "unavailable":
                skipped.append({"id": source.get("id"), "reason": "登记为 unavailable（来源探测失败）"})

            local_path = (repo_root / str(source.get("url", ""))) if source.get("kind") in {"self-owned", "self-generated"} else None
            if local_path is not None:
                record["local_path"] = str(source.get("url"))
                record["exists"] = local_path.exists()
            elif source.get("requires_credentials"):
                skipped.append({"id": source.get("id"), "reason": "需要账号凭据，脚本不代填"})
            elif source.get("status") == "unavailable":
                pass
            elif source.get("hf"):
                if not args.allow_network:
                    skipped.append({"id": source.get("id"), "reason": "未提供 --allow-network，只登记不下载"})
                else:
                    downloaded, hf_skipped, image_ids = download_hf(
                        source, raw_dir / source["id"], args.max_bytes, args.limit_images, repo_root, report
                    )
                    record["downloaded"] = downloaded
                    record["image_ids"] = image_ids
                    skipped.extend(hf_skipped)
            elif not args.allow_network:
                skipped.append({"id": source.get("id"), "reason": "未提供 --allow-network，只登记不下载"})
            else:
                try:
                    record["downloaded"] = [
                        fetch(str(source["url"]), raw_dir / f"{source['id']}.bin", args.max_bytes)
                    ]
                    report.add_output(raw_dir / f"{source['id']}.bin", "download")
                except Exception as exc:  # noqa: BLE001 - 网络失败要进报告而不是崩溃
                    skipped.append({"id": source.get("id"), "reason": f"下载失败：{exc}"})
            records.append(record)

        manifest = {
            "schema_version": 1,
            "version": f"public-{TASK_ID}-{report.run_id}",
            "sources": records,
            "skipped": skipped,
        }
        manifest_path = run_dir / "dataset_manifest.json"
        write_json(manifest_path, manifest)
        report.add_output(manifest_path, "manifest")
        report.dataset = {
            "version": manifest["version"],
            "manifest_sha256": sha256_file(manifest_path),
            "split": "raw",
            "sample_count": len(records),
        }
        report.metrics.update(
            sources=len(records),
            train_eligible=train_eligible,
            missing_license=missing_license,
            downloaded_files=sum(len(r["downloaded"]) for r in records),
            skipped=skipped,
        )
        if missing_license:
            report.warn(f"{len(missing_license)} 个来源未标注许可，仅记录不阻断")
        report.check("source_count", len(records) > 0, f"{len(records)} sources")
        if args.allow_network:
            report.check(
                "network_download_attempted",
                any(r["downloaded"] for r in records),
                f"{sum(len(r['downloaded']) for r in records)} 个文件落盘",
            )
        status = report.decide_status()
        if status == STATUS_BLOCKED:
            return emit(report, status, EXIT_APPROVAL, args)
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

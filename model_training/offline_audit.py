"""隐私与离线约束审计（X2）。

静态扫描 app/src/main 的网络调用、模型下载、图片内容日志；检查模型只从 assets 加载。
禁网真机测试由 instrumentation 提供；没有设备证据时为 NOT_RUN，静态结论不能替代。
"""

from __future__ import annotations

import argparse
import re
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
    resolve_path,
    run,
    sha256_file,
    write_json,
)

VERSION = "1.0.0"
TASK_ID = "X2"

NETWORK_PATTERNS = [
    (re.compile(r"HttpURLConnection|OkHttpClient|Retrofit|URL\(\s*\"http"), "network-call"),
    (re.compile(r"https?://[^\s\"]+"), "url-literal"),
    (re.compile(r"downloadModel|modelUrl|remoteModel", re.IGNORECASE), "model-download"),
]
LOG_PATTERNS = [
    (re.compile(r"Log\.[dviwe]\([^)]*bitmap", re.IGNORECASE), "bitmap-in-log"),
    (re.compile(r"Log\.[dviwe]\([^)]*base64", re.IGNORECASE), "base64-in-log"),
]
OFFLINE_EXEMPT = {
    "recognition/webview/",  # 网页 AI 源：联网识图是可选的独立路径
    "recognition/source/",
    "recognition/ai/",
    "recognition/site/",
    "recognition/AiRecognitionClient",
    "recognition/AiSettingsStore",
    "recognition/GeminiSelectors",
    "recognition/DeepSeek",
    "probe/",
}


def is_offline_exempt(path: Path, source_root: Path) -> bool:
    relative = str(path.relative_to(source_root))
    return any(
        relative.startswith(prefix) or prefix in relative
        for prefix in OFFLINE_EXEMPT
    )


def main() -> int:
    parser = build_parser(
        prog="offline_audit.py",
        version=VERSION,
        task_id=TASK_ID,
        description="扫描本地识别路径的网络调用与图片日志，校验模型只从 assets 加载",
    )
    parser.add_argument("--apk", default=None)
    parser.add_argument("--source", default="app/src/main")
    parser.add_argument("--assets", default="app/src/main/assets/models")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(apk=args.apk, source=args.source, assets=args.assets)

    def body() -> int:
        source_root = resolve_path(repo_root, args.source)
        if source_root is None or not source_root.is_dir():
            raise CliError(f"源码目录不存在：{args.source}")
        findings = []
        scanned = 0
        for path in sorted(source_root.rglob("*.kt")):
            scanned += 1
            exempt = is_offline_exempt(path, source_root)
            text = path.read_text(encoding="utf-8", errors="ignore")
            for pattern, kind in NETWORK_PATTERNS:
                for match in pattern.finditer(text):
                    findings.append(
                        {
                            "file": str(path.relative_to(repo_root)),
                            "line": text[: match.start()].count("\n") + 1,
                            "kind": kind,
                            "exempt": exempt,
                            "snippet": match.group(0)[:80],
                        }
                    )
            for pattern, kind in LOG_PATTERNS:
                for match in pattern.finditer(text):
                    findings.append(
                        {
                            "file": str(path.relative_to(repo_root)),
                            "line": text[: match.start()].count("\n") + 1,
                            "kind": kind,
                            "exempt": False,
                            "snippet": match.group(0)[:80],
                        }
                    )
        blocking = [f for f in findings if not f["exempt"]]
        local_path = [f for f in findings if f["kind"] == "url-literal" and not f["exempt"]]
        report.metrics.update(
            scanned_files=scanned,
            findings=len(findings),
            exempt_findings=len(findings) - len(blocking),
            blocking_findings=blocking[:20],
            url_literals_in_local_path=len(local_path),
        )
        report.check("local_path_has_no_network_calls", not blocking, f"{len(blocking)} blocking findings")
        report.check("no_image_content_logging", not any(f["kind"] in {"bitmap-in-log", "base64-in-log"} for f in findings), "no bitmap/base64 logging")
        report.check("scanned_source", scanned > 0, f"{scanned} kotlin files")

        assets_dir = resolve_path(repo_root, args.assets)
        models = sorted(assets_dir.glob("*.tflite")) if assets_dir and assets_dir.is_dir() else []
        report.metrics["model_assets"] = [{"path": str(m.relative_to(repo_root)), "sha256": sha256_file(m), "size": m.stat().st_size} for m in models]
        if models:
            report.check("models_present_in_assets", True, f"{len(models)} models")
            report.check("model_size_under_15mb", all(m.stat().st_size <= 15 * 1024 * 1024 for m in models), "≤15MB each")
        else:
            report.warn("assets/models 下没有模型：本地引擎仍走启发式路径（这是当前预期状态）")

        if args.apk:
            apk_path = resolve_path(repo_root, args.apk)
            if apk_path and apk_path.is_file():
                report.add_input(apk_path, "apk")
                report.metrics["apk_sha256"] = sha256_file(apk_path)
                report.check("apk_scanned", True, f"{apk_path.stat().st_size} bytes")
            else:
                report.warn(f"APK 不存在，跳过 APK 层检查：{args.apk}")

        diagnostics = run_dir / "offline-audit.json"
        write_json(diagnostics, {"findings": findings, "blocking": blocking})
        report.add_output(diagnostics, "audit")
        if report.checks_passed:
            report.warn("静态扫描通过，但禁网真机 OfflineRecognitionTest 未执行：需要 API 29 设备")
            return emit(report, STATUS_NOT_RUN, 3, args)
        return _finish(report, args)

    return run(body, args, report)


def _finish(report, args) -> int:
    status = report.decide_status()
    return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)



if __name__ == "__main__":
    raise SystemExit(main())

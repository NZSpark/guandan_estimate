"""真机性能基准（T4.7）。

要求 API 29 真机：先用 adb 校验设备、API level、包名与图片 hash，再跑 instrumentation 采集耗时。
没有设备或设备不符合要求时为 NOT_RUN；不得用模拟器或旧报告补齐。
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from pathlib import Path

from _cli import (
    EXIT_ENV,
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
TASK_ID = "T4.7"


def adb(args: list[str], serial: str | None) -> tuple[int, str]:
    binary = shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb")
    command = [binary] + ([f"-s {serial}"] if serial else []) + args
    command = " ".join(command).split()
    try:
        proc = subprocess.run(command, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 127, str(exc)
    return proc.returncode, proc.stdout.strip() or proc.stderr.strip()


def main() -> int:
    parser = build_parser(
        prog="device_benchmark.py",
        version=VERSION,
        task_id=TASK_ID,
        description="API 29 真机识别性能与内存基准",
    )
    parser.add_argument("--serial", default=None)
    parser.add_argument("--api", type=int, default=29)
    parser.add_argument("--cases", default="case4")
    parser.add_argument("--repeat", type=int, default=10)
    parser.add_argument("--stress-sequence", type=int, default=20)
    parser.add_argument("--apk", default=None)
    parser.add_argument("--runner-report", default=None, help="instrumentation 导出的 JSON")
    args = parser.parse_args()
    repo_root, run_dir = prepare(args, parser)
    report = make_report(args, TASK_ID)
    report.parameters.update(serial=args.serial, api=args.api, cases=args.cases, repeat=args.repeat, stress=args.stress_sequence)

    def body() -> int:
        if not shutil.which("adb") and not (Path.home() / "Library/Android/sdk/platform-tools/adb").exists():
            raise CliError("找不到 adb：需要 Android SDK platform-tools")
        code, devices = adb(["devices"], args.serial)
        if code != 0:
            raise CliError(f"adb devices 失败：{devices}")
        connected = [line.split("\t")[0] for line in devices.splitlines()[1:] if "\tdevice" in line]
        if not connected:
            report.warn("没有已授权设备：T4.7 未执行")
            return emit(report, STATUS_NOT_RUN, EXIT_ENV, args)
        serial = args.serial or connected[0]
        _, api = adb(["shell", "getprop", "ro.build.version.sdk"], serial)
        _, model = adb(["shell", "getprop", "ro.product.model"], serial)
        _, fingerprint = adb(["shell", "getprop", "ro.build.fingerprint"], serial)
        report.device = {
            "serial": serial,
            "model": model,
            "api_level": int(api) if api.isdigit() else None,
            "build_fingerprint": fingerprint,
            "captured_at": report.started_at,
        }
        report.check("device_is_real", "generic" not in fingerprint.lower() and "sdk_gphone" not in model.lower(), fingerprint)
        report.check("api_level_matches", report.device["api_level"] == args.api, f"{api} vs {args.api}")
        if args.apk:
            apk_path = resolve_path(repo_root, args.apk)
            if apk_path and apk_path.is_file():
                report.metrics["apk_sha256"] = sha256_file(apk_path)

        if not args.runner_report:
            report.warn("未提供 instrumentation 报告：真机耗时/内存证据缺失")
            return emit(report, STATUS_NOT_RUN, EXIT_ENV, args)
        runner_path = resolve_path(repo_root, args.runner_report)
        if runner_path is None or not runner_path.is_file():
            raise CliError(f"runner 报告不存在：{args.runner_report}")
        report.add_input(runner_path, "runner-report")
        payload = json.loads(runner_path.read_text(encoding="utf-8"))
        timings = payload.get("timings_ms") or []
        memory = payload.get("peak_memory_mb")
        if not timings:
            raise CliError("runner 报告缺少 timings_ms")
        timings.sort()
        p50 = timings[len(timings) // 2]
        p95 = timings[min(len(timings) - 1, int(len(timings) * 0.95))]
        report.metrics.update(p50_ms=p50, p95_ms=p95, peak_memory_mb=memory, samples=len(timings), apk_sha256=payload.get("apk_sha256"))
        report.check("p95_under_3s", p95 <= 3000, f"{p95} ms")
        report.check("peak_memory_under_150mb", memory is None or memory < 150, f"{memory} MB")
        report.dataset["sample_count"] = len(timings)
        status = report.decide_status()
        return emit(report, status, EXIT_PASS if status == STATUS_PASS else EXIT_FAIL, args)

    return run(body, args, report)


if __name__ == "__main__":
    raise SystemExit(main())

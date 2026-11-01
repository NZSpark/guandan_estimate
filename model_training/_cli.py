"""共享 CLI 骨架与 report.json 写出（doc/local_engine_tasks.md §7.2 / §7.3）。

约定：

- 每个脚本都提供 --help、--version、--repo-root、--output-dir、--run-id。
- --output-dir 是本次独立运行目录；未提供时使用 output/runs/<run_id>/<task_id>/。
- 运行目录已存在且非空时拒绝覆盖（退出码 2）。
- 退出码：0 PASS / 1 FAIL / 2 参数或输入错误 / 3 环境不可用 / 4 需人工签核。
- 报告缺必需字段、含 NaN/Infinity、样本数为 0 时不得写成 PASS。

本模块只用标准库，避免在没有 TensorFlow / OpenCV 的环境里连 --help 都跑不起来。
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import math
import os
import platform
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

SCHEMA_VERSION = 1

EXIT_PASS = 0
EXIT_FAIL = 1
EXIT_USAGE = 2
EXIT_ENV = 3
EXIT_APPROVAL = 4

STATUS_PASS = "PASS"
STATUS_FAIL = "FAIL"
STATUS_NOT_RUN = "NOT_RUN"
STATUS_BLOCKED = "BLOCKED"

REQUIRED_REPORT_FIELDS = (
    "schema_version",
    "run_id",
    "task_id",
    "status",
    "started_at",
    "finished_at",
    "command",
    "parameters",
    "environment",
    "inputs",
    "dataset",
    "seed",
    "outputs",
    "metrics",
    "checks",
    "approvals",
    "device",
    "warnings",
    "errors",
)


class CliError(Exception):
    """参数、配置或输入错误 → 退出码 2。"""

    exit_code = EXIT_USAGE


class EnvironmentUnavailable(CliError):
    """外部依赖 / 设备不可用 → 退出码 3。"""

    exit_code = EXIT_ENV


class ApprovalRequired(CliError):
    """缺少必需人工签核 → 退出码 4。"""

    exit_code = EXIT_APPROVAL


# ---------------------------------------------------------------- 基础工具


def utc_now() -> str:
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat()


def default_repo_root() -> Path:
    """从脚本位置向上找到仓库根（含 settings.gradle.kts 或 .git 的目录）。"""
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "settings.gradle.kts").exists() or (parent / ".git").exists():
            return parent
    return here.parent.parent


def resolve_path(repo_root: Path, raw: str | os.PathLike[str] | None) -> Path | None:
    """相对路径一律相对仓库根解析，不依赖当前工作目录。"""
    if raw is None or str(raw) == "":
        return None
    path = Path(raw)
    return path if path.is_absolute() else (repo_root / path)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_json(payload: Any) -> str:
    return sha256_bytes(
        json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    )


def rel(repo_root: Path, path: Path) -> str:
    try:
        return str(Path(path).resolve().relative_to(Path(repo_root).resolve()))
    except (ValueError, OSError):
        return str(path)


def file_entry(repo_root: Path, path: Path | None, role: str | None = None) -> dict[str, Any] | None:
    """report.json 的 inputs/outputs 条目；文件不存在时 sha256 为 null，便于门禁识别。"""
    if path is None:
        return None
    resolved = resolve_path(repo_root, path)
    assert resolved is not None
    entry: dict[str, Any] = {"path": rel(repo_root, resolved), "sha256": None}
    if resolved.is_file():
        entry["sha256"] = sha256_file(resolved)
        entry["size"] = resolved.stat().st_size
    if role:
        entry["role"] = role
    return entry


def new_run_id(task_id: str) -> str:
    stamp = _dt.datetime.now(_dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{task_id}-{stamp}-{os.getpid()}"


def base_environment() -> dict[str, Any]:
    deps: dict[str, str] = {}
    for name in ("numpy", "pillow", "pyyaml", "torch", "tensorflow", "ultralytics"):
        module = "PIL" if name == "pillow" else name
        try:
            import importlib

            imported = importlib.import_module(module)
            deps[name] = str(getattr(imported, "__version__", "unknown"))
        except Exception:  # noqa: BLE001 - 缺依赖不应阻断报告生成
            deps[name] = "missing"
    return {
        "os": f"{platform.system()} {platform.release()} {platform.machine()}",
        "python": platform.python_version(),
        "java": os.environ.get("JAVA_HOME", ""),
        "gradle": "",
        "dependencies": deps,
    }


def metric(value: float, digits: int = 6) -> float:
    """指标统一保留位数，并拒绝非有限值。"""
    number = float(value)
    if not math.isfinite(number):
        raise CliError(f"指标不是有限数：{value}")
    return round(number, digits)


def wilson_interval(successes: int, total: int, z: float = 1.96) -> tuple[float, float]:
    if total <= 0:
        return (0.0, 1.0)
    p = successes / total
    denominator = 1 + z * z / total
    centre = (p + z * z / (2 * total)) / denominator
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denominator
    return (max(0.0, centre - half), min(1.0, centre + half))


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise CliError(f"JSON 解析失败：{path}：{exc}") from exc


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[Mapping[str, Any]]) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
            count += 1
    return count


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def load_config(repo_root: Path, raw: str | None) -> dict[str, Any]:
    """读 YAML 或 JSON 配置；PyYAML 缺失时只有 JSON 配置可用。"""
    if not raw:
        return {}
    path = require_file(repo_root, raw, "配置文件")
    text = path.read_text(encoding="utf-8")
    if path.suffix.lower() == ".json":
        return json.loads(text)
    try:
        import yaml  # type: ignore
    except ModuleNotFoundError as exc:
        raise CliError(f"读取 {path.name} 需要 PyYAML：pip install pyyaml") from exc
    loaded = yaml.safe_load(text)
    return loaded or {}


def require_file(repo_root: Path, raw: str | Path | None, label: str) -> Path:
    path = resolve_path(repo_root, raw)
    if path is None or not path.is_file():
        raise CliError(f"{label} 不存在：{raw}")
    return path


def require_dir(repo_root: Path, raw: str | Path | None, label: str) -> Path:
    path = resolve_path(repo_root, raw)
    if path is None or not path.is_dir():
        raise CliError(f"{label} 不存在：{raw}")
    return path


# ---------------------------------------------------------------- 报告


def _contains_nonfinite(value: Any) -> bool:
    if isinstance(value, float):
        return not math.isfinite(value)
    if isinstance(value, Mapping):
        return any(_contains_nonfinite(item) for item in value.values())
    if isinstance(value, (list, tuple, set)):
        return any(_contains_nonfinite(item) for item in value)
    return False


@dataclass
class Check:
    name: str
    passed: bool
    detail: str = ""

    def to_json(self) -> dict[str, Any]:
        return {"name": self.name, "passed": bool(self.passed), "detail": self.detail}


@dataclass
class Report:
    task_id: str
    run_id: str
    command: str
    repo_root: Path
    output_dir: Path
    parameters: dict[str, Any] = field(default_factory=dict)
    environment: dict[str, Any] = field(default_factory=dict)
    inputs: list[dict[str, Any]] = field(default_factory=list)
    dataset: dict[str, Any] = field(
        default_factory=lambda: {"version": "", "manifest_sha256": "", "split": "", "sample_count": 0}
    )
    seed: int | None = None
    outputs: list[dict[str, Any]] = field(default_factory=list)
    metrics: dict[str, Any] = field(default_factory=dict)
    checks: list[Check] = field(default_factory=list)
    approvals: list[dict[str, Any]] = field(default_factory=list)
    device: dict[str, Any] | None = None
    warnings: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    started_at: str = field(default_factory=utc_now)

    # --- 收集 ---

    def add_input(self, path: Path | None, role: str | None = None) -> None:
        entry = file_entry(self.repo_root, path, role)
        if entry is not None:
            self.inputs.append(entry)

    def add_output(self, path: Path | None, role: str | None = None) -> None:
        entry = file_entry(self.repo_root, path, role)
        if entry is not None:
            self.outputs.append(entry)

    def check(self, name: str, passed: bool, detail: str = "") -> bool:
        self.checks.append(Check(name, passed, detail))
        return bool(passed)

    def warn(self, message: str) -> None:
        if message not in self.warnings:
            self.warnings.append(message)

    def error(self, message: str) -> None:
        if message not in self.errors:
            self.errors.append(message)

    # --- 状态判定 ---

    @property
    def checks_passed(self) -> bool:
        return bool(self.checks) and all(check.passed for check in self.checks)

    def decide_status(
        self, *, blocked_reason: str | None = None, not_run_reason: str | None = None
    ) -> str:
        """状态由证据决定，不由脚本“跑完”决定。"""
        if blocked_reason:
            self.error(blocked_reason)
            return STATUS_BLOCKED
        if not_run_reason:
            self.warn(not_run_reason)
            return STATUS_NOT_RUN
        if self.errors:
            return STATUS_FAIL
        if not self.checks or not self.checks_passed:
            return STATUS_FAIL
        missing = [a for a in self.approvals if a.get("required") and not a.get("valid")]
        if missing:
            for approval in missing:
                self.error(f"缺少有效签核：{approval.get('type')}")
            return STATUS_BLOCKED
        return STATUS_PASS

    # --- 序列化 ---

    def to_json(self, status: str) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "schema_version": SCHEMA_VERSION,
            "run_id": self.run_id,
            "task_id": self.task_id,
            "status": status,
            "started_at": self.started_at,
            "finished_at": utc_now(),
            "command": self.command,
            "parameters": self.parameters,
            "environment": self.environment,
            "inputs": self.inputs,
            "dataset": self.dataset,
            "seed": self.seed,
            "outputs": self.outputs,
            "metrics": self.metrics,
            "checks": [check.to_json() for check in self.checks],
            "approvals": self.approvals,
            "device": self.device,
            "warnings": self.warnings,
            "errors": self.errors,
        }
        return payload

    def validate(self) -> list[str]:
        """返回报告不符合 §7.3 的问题列表；非空时不得写成 PASS。"""
        problems: list[str] = []
        payload = self.to_json(STATUS_PASS)
        for name in REQUIRED_REPORT_FIELDS:
            if name not in payload:
                problems.append(f"缺少必需字段 {name}")
        if _contains_nonfinite(payload):
            problems.append("报告中出现 NaN/Infinity")
        if self.dataset.get("sample_count", 0) == 0 and any(
            check.name.startswith("sample") for check in self.checks
        ):
            problems.append("样本数为 0 却声明了样本检查")
        return problems

    def write(self, status: str, report_path: Path | None = None) -> Path:
        problems = self.validate()
        if problems and status == STATUS_PASS:
            status = STATUS_FAIL
            for problem in problems:
                self.error(problem)
        target = report_path or (self.output_dir / "report.json")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps(self.to_json(status), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return target


# ---------------------------------------------------------------- argparse


def build_parser(
    *,
    prog: str,
    version: str,
    task_id: str,
    description: str,
    epilog: str | None = None,
    need_seed: bool = False,
) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog=prog,
        description=f"[{task_id}] {description}",
        epilog=epilog,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--version", action="version", version=f"%(prog)s {version}")
    parser.add_argument("--repo-root", default=None, help="仓库根目录；默认从脚本位置定位")
    parser.add_argument("--output-dir", default=None, help="本次独立运行目录；已存在且非空时拒绝覆盖")
    parser.add_argument("--run-id", default=None, help="唯一执行标识；默认按时间生成")
    parser.add_argument("--config", default=None, help="配置文件；hash 与展开参数写入报告")
    parser.add_argument("--json", dest="json_report", default=None, help="报告路径；默认 <run-dir>/report.json")
    parser.add_argument("--strict", action="store_true", help="严格门禁：缺字段/样本不足/缺签核一律不通过")
    parser.add_argument("--task-id", default=task_id, help=argparse.SUPPRESS)
    if need_seed:
        parser.add_argument("--seed", type=int, required=True, help="随机种子（必填并写入报告）")
    else:
        parser.add_argument("--seed", type=int, default=None, help="随机种子")
    return parser


def prepare(args: argparse.Namespace, parser: argparse.ArgumentParser) -> tuple[Path, Path]:
    """解析 repo-root / output-dir，创建运行目录，返回 (repo_root, run_dir)。"""
    repo_root = Path(args.repo_root).resolve() if args.repo_root else default_repo_root()
    if not repo_root.is_dir():
        parser.error(f"--repo-root 不是目录：{repo_root}")
    run_id = args.run_id or new_run_id(args.task_id)
    if args.output_dir:
        run_dir = Path(args.output_dir).resolve()
    else:
        run_dir = repo_root / "output" / "runs" / run_id / args.task_id
    if run_dir.exists() and any(run_dir.iterdir()):
        parser.error(f"运行目录已存在且非空，拒绝覆盖：{run_dir}")
    run_dir.mkdir(parents=True, exist_ok=True)
    args._repo_root = repo_root  # type: ignore[attr-defined]
    args._run_dir = run_dir  # type: ignore[attr-defined]
    args._run_id = run_id  # type: ignore[attr-defined]
    return repo_root, run_dir


def make_report(args: argparse.Namespace, task_id: str | None = None) -> Report:
    repo_root: Path = args._repo_root  # type: ignore[attr-defined]
    run_dir: Path = args._run_dir  # type: ignore[attr-defined]
    report = Report(
        task_id=task_id or args.task_id,
        run_id=args._run_id,  # type: ignore[attr-defined]
        command=" ".join([Path(sys.argv[0]).name, *sys.argv[1:]]),
        repo_root=repo_root,
        output_dir=run_dir,
        environment=base_environment(),
        seed=getattr(args, "seed", None),
    )
    if getattr(args, "config", None):
        config_path = resolve_path(repo_root, args.config)
        report.add_input(config_path, "config")
        report.parameters["config"] = str(args.config)
        report.parameters["config_sha256"] = file_entry(repo_root, config_path, "config")["sha256"] if config_path and config_path.is_file() else None
    return report


def emit(report: Report, status: str, exit_code: int, args: argparse.Namespace) -> int:
    target: Path | None = None
    if getattr(args, "json_report", None):
        raw = Path(args.json_report)
        target = raw if raw.is_absolute() else (report.output_dir / raw.name)
    path = report.write(status, target)
    sys.stdout.write(f"[{report.task_id}] {status} -> {rel(report.repo_root, path)}\n")
    if status != STATUS_PASS:
        for error in report.errors:
            sys.stderr.write(f"  error: {error}\n")
        for warning in report.warnings:
            sys.stderr.write(f"  warn: {warning}\n")
    return exit_code


def run(main_fn, args: argparse.Namespace, report: Report) -> int:
    """统一异常 → 退出码映射，避免脚本“吞异常后返回成功”。"""
    try:
        return main_fn()
    except ApprovalRequired as exc:
        report.error(str(exc))
        return emit(report, STATUS_BLOCKED, exc.exit_code, args)
    except EnvironmentUnavailable as exc:
        report.error(str(exc))
        return emit(report, STATUS_NOT_RUN, exc.exit_code, args)
    except CliError as exc:
        report.error(str(exc))
        return emit(report, STATUS_FAIL, exc.exit_code, args)
    except FileNotFoundError as exc:
        report.error(f"输入缺失：{exc}")
        return emit(report, STATUS_FAIL, EXIT_USAGE, args)


# ---------------------------------------------------------------- 签核


APPROVAL_FIELDS = {
    "golden-definition": ("images", "reviewer", "reviewed_at", "version"),
    "photo-visibility": ("images", "reviewer", "reviewed_at", "version"),
    "correction-approval": ("corrections", "reviewer", "reviewed_at", "version"),
    "release-qa": ("release", "test_report", "device_report", "license_report", "rollback", "reviewer", "reviewed_at", "version"),
}


def load_approvals(repo_root: Path, types: Sequence[str]) -> list[dict[str, Any]]:
    """读取 model_training/approvals/<type>.json，只做格式与绑定检查，不代签。"""
    directory = repo_root / "model_training" / "approvals"
    results: list[dict[str, Any]] = []
    for approval_type in types:
        path = directory / f"{approval_type}.json"
        entry: dict[str, Any] = {
            "type": approval_type,
            "required": True,
            "valid": False,
            "path": rel(repo_root, path),
            "sha256": None,
            "missing_fields": [],
        }
        if path.is_file():
            entry["sha256"] = sha256_file(path)
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                entry["reason"] = f"JSON 解析失败：{exc}"
                results.append(entry)
                continue
            missing = [f for f in APPROVAL_FIELDS.get(approval_type, ()) if not payload.get(f)]
            entry["missing_fields"] = missing
            entry["reviewer"] = payload.get("reviewer")
            entry["reviewed_at"] = payload.get("reviewed_at")
            entry["version"] = payload.get("version")
            entry["valid"] = not missing
            if missing:
                entry["reason"] = "签核缺少字段：" + ", ".join(missing)
            results.append(entry)
        else:
            entry["reason"] = "签核文件不存在"
            results.append(entry)
    return results

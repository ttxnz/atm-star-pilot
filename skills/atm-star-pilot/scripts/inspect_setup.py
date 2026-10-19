"""Read-only ATM Star Pilot setup inspection; session authentication data is never read."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import zipfile


ARTIFACTS = {
    "core": ("atm-star-pilot-0.1.0.jar", "dev/atmpilot/PilotMod.class"),
    "runtime": ("atm-star-pilot-0.1.0-automation.jar", "dev/atmpilot/runtime/AutomationPlugin.class"),
}


def artifact(path: Path, entry: str) -> dict:
    result = {"path": str(path), "exists": path.is_file()}
    if not path.is_file():
        return result
    result["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    try:
        with zipfile.ZipFile(path) as jar:
            result["entrypointPresent"] = entry in jar.namelist()
            result["integrityOk"] = jar.testzip() is None
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        result["errorType"] = type(error).__name__
    return result


def locate_project(given: str | None) -> Path | None:
    requested = given or os.environ.get("ATM_PILOT_PROJECT")
    if requested:
        return Path(requested).expanduser().resolve()
    current = Path.cwd().resolve()
    for candidate in (current, *current.parents):
        if (candidate / "mcp_server.py").is_file() and (candidate / "build.gradle").is_file():
            return candidate
    return None


def inspect(project: Path | None, instance: Path | None) -> dict:
    report = {"readOnly": True, "pythonVersion": sys.version.split()[0], "project": str(project) if project else None, "missing": []}
    if project is None or not project.is_dir():
        report["missing"].append("Project directory; pass --project or set ATM_PILOT_PROJECT")
        return report
    names = ("mcp_server.py", "build.gradle", "work_queue.py", "tools/run_queue.py", "computer-programs/loot-router.lua")
    report["projectFiles"] = {name: (project / name).is_file() for name in names}
    report["missing"].extend(name for name, exists in report["projectFiles"].items() if not exists)
    report["artifacts"] = {}
    for kind, (name, entry) in ARTIFACTS.items():
        built = project / "build/libs" / name
        result = artifact(built if built.is_file() else project / "release" / name, entry)
        report["artifacts"][kind] = result
        if not result.get("entrypointPresent") or not result.get("integrityOk"):
            report["missing"].append("Valid " + kind + " artifact")
    if instance is None:
        report["instance"] = None
        return report
    cfg = instance / "config/atmpilot"
    report["instance"] = {
        "path": str(instance), "modsDirectoryExists": (instance / "mods").is_dir(),
        "sessionFileExists": (cfg / "session.json").is_file(), "sessionContentsRead": False,
        "installedArtifacts": {
            "core": artifact(instance / "mods" / ARTIFACTS["core"][0], ARTIFACTS["core"][1]),
            "runtime": artifact(cfg / "runtime/automation.jar", ARTIFACTS["runtime"][1]),
        },
    }
    for kind, installed in report["instance"]["installedArtifacts"].items():
        installed["matchesProjectArtifact"] = bool(installed.get("sha256")) and installed["sha256"] == report["artifacts"][kind].get("sha256")
    rules = cfg / "server-rules.json"
    report["instance"]["rulesFileExists"] = rules.is_file()
    if rules.is_file():
        try:
            data = json.loads(rules.read_text(encoding="utf-8-sig"))
            report["instance"]["rulesSummary"] = {
                "buildAreaCount": len(data.get("buildAreas", [])),
                "manualControlMode": data.get("manualControlPolicy", {}).get("mode"),
                "sharedWithdrawalEnabled": data.get("allowSharedStorageWithdraw", False),
                "nativeMapTeleportEnabled": data.get("allowNativeMapTeleport", False),
            }
        except (OSError, ValueError, TypeError, AttributeError):
            report["instance"]["rulesParseError"] = True
    checkpoint = cfg / "work-queue-state.json"
    if checkpoint.is_file():
        try:
            data = json.loads(checkpoint.read_text(encoding="utf-8-sig"))
            report["instance"]["queueSummary"] = {"status": data.get("status"), "completedStageCount": len(data.get("completed", [])), "hasInflight": bool(data.get("inflight"))}
        except (OSError, ValueError, TypeError, AttributeError):
            report["instance"]["queueParseError"] = True
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", help="Source project or extracted share-package root")
    parser.add_argument("--instance", help="Optional instance root; session contents are never read")
    args = parser.parse_args()
    selected = args.instance or os.environ.get("ATM_PILOT_INSTANCE")
    instance = Path(selected).expanduser().resolve() if selected else None
    report = inspect(locate_project(args.project), instance)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report["missing"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

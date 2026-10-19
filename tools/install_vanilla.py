"""Install the client-only vanilla-mode build into a separate stopped 1.21.1 NeoForge instance."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import uuid

ROOT = Path(__file__).resolve().parents[1]


def install(instance: Path, core: Path, replace: bool = False) -> Path:
    instance = instance.resolve(strict=True)
    mods = instance / "mods"
    if not mods.is_dir():
        raise ValueError("Select an existing dedicated instance containing mods/")
    foreign = [p.name for p in mods.glob("*.jar") if p.name != "atm-star-pilot-0.1.0.jar"]
    if foreign:
        raise ValueError("Use a separate vanilla-content instance; existing mod jars: " + ", ".join(foreign[:8]))
    target = mods / "atm-star-pilot-0.1.0.jar"
    if not core.is_file():
        raise ValueError("Core JAR missing from release/")
    if target.exists() and target.read_bytes() != core.read_bytes() and not replace:
        raise ValueError("Different control mod exists; exit the instance and use --replace-existing")
    # Do not inspect or copy tokens. A live local session is a reason to refuse replacement.
    session = instance / "config/atmpilot/session.json"
    if session.exists():
        try:
            pid = int(json.loads(session.read_text(encoding="utf-8-sig"))["pid"])
            os.kill(pid, 0)
        except (ProcessLookupError, FileNotFoundError):
            pass
        except (KeyError, ValueError, PermissionError, OSError) as exc:
            raise ValueError("Cannot confirm this instance is stopped; close it before installation") from exc
        else:
            raise ValueError("This instance still has a live process; exit it before installation")
    temporary = target.with_name(target.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        shutil.copyfile(core, temporary)
        if hashlib.sha256(temporary.read_bytes()).digest() != hashlib.sha256(core.read_bytes()).digest():
            raise ValueError("JAR copy checksum mismatch")
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)
    config = instance / "config/atmpilot"
    config.mkdir(parents=True, exist_ok=True)
    rules = config / "server-rules.json"
    if not rules.exists():
        rules.write_text(json.dumps({"buildAreas": [], "allowExistingSurvivalFlight": False,
            "allowSharedStorageWithdraw": False, "survivalGuard": {"enabled": False},
            "deathRecovery": {"enabled": False}}, indent=2), encoding="utf-8")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument("--replace-existing", action="store_true")
    args = parser.parse_args()
    target = install(args.instance, ROOT / "release/atm-star-pilot-0.1.0.jar", args.replace_existing)
    print("Installed:", target)
    print("No world was opened and no run was started. Read VANILLA_MODE.zh-CN.md.")


if __name__ == "__main__":
    main()

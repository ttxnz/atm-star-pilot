"""Install only the two bundled ATM Pilot artifacts into a deliberately selected, stopped instance."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def copy_atomic(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        shutil.copyfile(source, temporary)
        if digest(temporary) != digest(source):
            raise RuntimeError("Copy checksum mismatch")
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path, required=True,
                        help="Your instance root containing mods/; exit this game instance first")
    parser.add_argument("--replace-existing", action="store_true",
                        help="Replace different same-named ATM Pilot artifacts; existing rules are retained")
    args = parser.parse_args()
    instance = args.instance.expanduser().resolve(strict=True)
    if not instance.is_dir() or not (instance / "mods").is_dir():
        raise RuntimeError("Select an existing Minecraft instance root containing a mods directory")
    artifacts = (
        (ROOT / "release/atm-star-pilot-0.1.0.jar", instance / "mods/atm-star-pilot-0.1.0.jar",
         "dev/atmpilot/PilotMod.class"),
        (ROOT / "release/atm-star-pilot-0.1.0-automation.jar",
         instance / "config/atmpilot/runtime/automation.jar", "dev/atmpilot/runtime/AutomationPlugin.class"),
    )
    for source, target, entry in artifacts:
        with zipfile.ZipFile(source) as archive:
            if entry not in archive.namelist():
                raise RuntimeError("Bundled artifact does not contain the required entry point")
        if target.exists() and digest(target) != digest(source) and not args.replace_existing:
            raise RuntimeError("A different ATM Pilot artifact already exists; exit the game and explicitly use --replace-existing")
    # Never read, print, or copy the instance's session or other runtime data.
    for source, target, _ in artifacts:
        if not target.exists() or digest(target) != digest(source):
            copy_atomic(source, target)
        print("Installed", target, "SHA256", digest(target))
    directory = instance / "config/atmpilot"
    rules = directory / "server-rules.json"
    if not rules.exists():
        copy_atomic(ROOT / "server-rules.example.json", rules)
        print("Created default rules with no authorized construction areas.")
    else:
        print("Existing server-rules.json was retained.")
    for source in (ROOT / "workflows").glob("*.json"):
        target = directory / "workflows" / source.name
        if not target.exists():
            copy_atomic(source, target)
    for source in (ROOT / "computer-programs").glob("*.lua"):
        target = directory / "computer-programs" / source.name
        if not target.exists():
            copy_atomic(source, target)
    print("Installation finished. No game was launched or restarted. Configure your own areas before construction.")


if __name__ == "__main__":
    main()

"""Create a self-contained vanilla-mode experimental distribution, excluding personal data."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    core = ROOT / "build/libs/atm-star-pilot-0.1.0.jar"
    if not core.is_file():
        raise SystemExit("Run gradlew assemble test first")
    files = {
        "release/atm-star-pilot-0.1.0.jar": core,
        **{name: ROOT / name for name in ("mcp_server.py", "workflow_store.py", "LICENSE", "VANILLA_MODE.zh-CN.md")},
        **{"tools/" + name: ROOT / "tools" / name for name in ("install_vanilla.py", "vanilla_runner.py")},
    }
    manifest = {"mode": "vanilla_survival", "status": "experimental-not-end-to-end-validated", "minecraft": "1.21.1",
        "neoforge": "21.1.251", "jeiRequired": False,
        "files": {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in files.items()}}
    output = ROOT / "dist/vanilla-survival-pilot-experimental-1.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, path in files.items():
            archive.write(path, name)
        archive.writestr("MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        for name, sha in manifest["files"].items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == sha
    print(output)
    print("SHA256", hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()

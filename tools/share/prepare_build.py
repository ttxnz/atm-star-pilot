"""Prepare the compile-only JEI dependency from the recipient's own matching installation."""
from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jei", type=Path, required=True, help="JEI JAR from your compatible ATM10 instance")
    args = parser.parse_args()
    source = args.jei.expanduser().resolve(strict=True)
    with zipfile.ZipFile(source) as archive:
        if not any(name.startswith("mezz/jei/api/") and name.endswith(".class") for name in archive.namelist()):
            raise RuntimeError("This JAR does not contain the JEI API")
    target = ROOT / "libs/jei-api.jar"
    target.parent.mkdir(exist_ok=True)
    if source != target.resolve():
        shutil.copyfile(source, target)
    print("Prepared local compile-only dependency at", target)
    print("Set JAVA_HOME to your JDK 21, then run gradlew.bat assemble.")


if __name__ == "__main__":
    main()

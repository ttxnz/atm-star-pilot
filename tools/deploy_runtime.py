"""Build and atomically replace the automation JAR. Running jobs keep their frozen old snapshot."""
from __future__ import annotations
import argparse
import hashlib
import os
import pathlib
import subprocess
import sys
import uuid
import zipfile

ROOT=pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT))
from mcp_server import DEFAULT_SESSION


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session",type=pathlib.Path,default=DEFAULT_SESSION)
    parser.add_argument("--no-build",action="store_true")
    args=parser.parse_args()
    if not args.no_build:
        environment=dict(os.environ)
        subprocess.run([str(ROOT/"gradlew.bat"),"automationJar"],cwd=ROOT,env=environment,check=True)
    source=ROOT/"build/libs/atm-star-pilot-0.1.0-automation.jar"
    with zipfile.ZipFile(source) as jar:
        if "dev/atmpilot/runtime/AutomationPlugin.class" not in jar.namelist():
            raise RuntimeError("Automation entry point is missing")
    data=source.read_bytes()
    target=args.session.parent/"runtime"/"automation.jar"
    target.parent.mkdir(parents=True,exist_ok=True)
    temporary=target.with_name("automation-"+uuid.uuid4().hex+".tmp")
    try:
        temporary.write_bytes(data);os.replace(temporary,target)
    finally:
        temporary.unlink(missing_ok=True)
    print("Installed",target)
    print("SHA256",hashlib.sha256(data).hexdigest())
    print("Future jobs use the new module; running jobs are unchanged. Minecraft was not restarted.")


if __name__=="__main__":main()

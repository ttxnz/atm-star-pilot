"""Stage this mod's core JAR; install once after the current game process naturally exits."""
from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import uuid
import zipfile
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from mcp_server import DEFAULT_SESSION

JAR_NAME = "atm-star-pilot-0.1.0.jar"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, data):
    data["updatedAt"] = datetime.now(timezone.utc).isoformat()
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    temporary.write_text(json.dumps(data, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def process_handle(pid):
    if os.name != "nt":
        raise RuntimeError("This one-shot installer is for the current Windows host")
    api = ctypes.WinDLL("kernel32", use_last_error=True)
    api.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    api.OpenProcess.restype = wintypes.HANDLE
    api.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    api.GetProcessTimes.restype = wintypes.BOOL
    api.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
    api.WaitForSingleObject.restype = wintypes.DWORD
    api.CloseHandle.argtypes = [wintypes.HANDLE]
    api.CloseHandle.restype = wintypes.BOOL
    handle = api.OpenProcess(0x100000 | 0x1000, False, pid)
    if not handle:
        if ctypes.get_last_error() == 87:
            return api, None, None
        raise ctypes.WinError(ctypes.get_last_error())
    times = [wintypes.FILETIME() for _ in range(4)]
    if not api.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
        api.CloseHandle(handle)
        raise ctypes.WinError(ctypes.get_last_error())
    created = (times[0].dwHighDateTime << 32) | times[0].dwLowDateTime
    return api, handle, created


def validate_jar(path):
    with zipfile.ZipFile(path) as jar:
        needed = {"META-INF/neoforge.mods.toml", "dev/atmpilot/PilotMod.class",
                  "dev/atmpilot/SurvivalReflex.class", "dev/atmpilot/TransportRules.class"}
        if not needed.issubset(jar.namelist()):
            raise RuntimeError("The staged file is not the current ATM Pilot core")


def install(manifest):
    """Called only after the recorded process has exited; all filesystem preconditions are checked."""
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("state") == "superseded":
        return
    directory = manifest.parent.parent.resolve()
    instance = directory.parent.parent
    target = instance / "mods" / JAR_NAME
    source = manifest.parent / (data["sha256"] + ".jar")
    if directory.name != "atmpilot" or directory.parent.name != "config":
        raise RuntimeError("Unexpected instance update directory")
    if source.resolve().parent != manifest.parent.resolve() or target.resolve().parent != (instance / "mods").resolve():
        raise RuntimeError("Update path escaped this mod's instance directories")
    validate_jar(source)
    if digest(source) != data["sha256"]:
        raise RuntimeError("Staged core checksum changed")
    current = digest(target)
    if current == data["sha256"]:
        data["state"] = "already_installed"
        save(manifest, data)
        return
    if current != data["previousSha256"]:
        raise RuntimeError("Installed core changed since staging; retain it for review")
    backups = directory / "install-backups"
    backups.mkdir(exist_ok=True)
    backup = backups / (current + ".jar")
    if not backup.exists():
        shutil.copyfile(target, backup)
    if digest(backup) != current:
        raise RuntimeError("Core backup checksum is invalid")
    temporary = target.with_name(JAR_NAME + "." + uuid.uuid4().hex + ".tmp")
    try:
        shutil.copyfile(source, temporary)
        if digest(temporary) != data["sha256"]:
            raise RuntimeError("Temporary core checksum is invalid")
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)
    if digest(target) != data["sha256"]:
        raise RuntimeError("Installed core verification failed")
    data["state"] = "installed_verified"
    data["backup"] = str(backup)
    save(manifest, data)


def worker(manifest):
    data = json.loads(manifest.read_text(encoding="utf-8"))
    api, handle, created = process_handle(data["gamePid"])
    try:
        if handle and created == data["gameCreated"]:
            data["state"] = "waiting_for_game_exit"
            data["workerPid"] = os.getpid()
            save(manifest, data)
            if api.WaitForSingleObject(handle, 0xFFFFFFFF) != 0:
                raise RuntimeError("Could not verify the recorded game process exit")
    finally:
        if handle:
            api.CloseHandle(handle)
    install(manifest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", type=pathlib.Path, default=DEFAULT_SESSION)
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--worker", type=pathlib.Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        try:
            worker(args.worker)
        except Exception as error:
            data = json.loads(args.worker.read_text(encoding="utf-8"))
            data.update(state="needs_review", error=str(error))
            save(args.worker, data)
            raise
        return
    if not args.no_build:
        env = dict(os.environ)
        subprocess.run([str(ROOT / "gradlew.bat"), "build"], cwd=ROOT, env=env, check=True)
    source = ROOT / "build" / "libs" / JAR_NAME
    validate_jar(source)
    session = json.loads(args.session.read_text(encoding="utf-8-sig"))
    api, handle, created = process_handle(int(session["pid"]))
    if not handle:
        raise RuntimeError("The recorded game process is no longer running; inspect the current session")
    api.CloseHandle(handle)
    directory = args.session.resolve().parent
    target = directory.parent.parent / "mods" / JAR_NAME
    sha = digest(source)
    updates = directory / "core-updates"
    updates.mkdir(exist_ok=True)
    staged = updates / (sha + ".jar")
    shutil.copyfile(source, staged)
    manifest = updates / (sha + "-" + str(session["pid"]) + ".json")
    if manifest.exists():
        previous = json.loads(manifest.read_text(encoding="utf-8"))
        if previous.get("state") == "waiting_for_game_exit":
            _, existing, _ = process_handle(int(previous.get("workerPid", 0)))
            if existing:
                api.CloseHandle(existing)
                print("Already staged; one-shot installer is waiting for the current game to exit.")
                print("Manifest", manifest)
                return
    for old_manifest in updates.glob("*.json"):
        old = json.loads(old_manifest.read_text(encoding="utf-8"))
        if old_manifest != manifest and old.get("state") in {"waiting_for_game_exit", "staged"}:
            old.update(state="superseded", supersededBy=sha)
            save(old_manifest, old)
    save(manifest, {"state": "staged", "sha256": sha, "previousSha256": digest(target),
                    "gamePid": int(session["pid"]), "gameCreated": created,
                    "scope": "Only this core JAR, after natural game exit; no restart or other mods modified"})
    with (updates / "installer.log").open("ab") as log:
        child = subprocess.Popen([sys.executable, str(pathlib.Path(__file__).resolve()), "--worker", str(manifest)],
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW, close_fds=True)
    print("Core staged", sha)
    print("One-shot installer", child.pid, "will wait for natural game exit.")
    print("Manifest", manifest)


if __name__ == "__main__":
    main()

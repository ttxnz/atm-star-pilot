"""Build a source + precompiled ATM Pilot share ZIP without copying any live instance data."""
from __future__ import annotations

import argparse
import ast
from datetime import date, datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TOP = "atm-star-pilot"
CORE_NAME = "atm-star-pilot-0.1.0.jar"
RUNTIME_NAME = "atm-star-pilot-0.1.0-automation.jar"
EXCLUDED = (
    "Live session files, tokens, credentials and instance connection settings",
    "Personal AGENTS/README/progress/route documents, authorizations, coordinates and game snapshots",
    "Runtime evidence, UUID samples, screenshots, logs, saved worlds and full modpacks",
    ".git, .gradle, .tools, build caches, Python caches and prior distribution archives",
    "Third-party JEI/mod JARs and redundant generated source JARs",
)
SAFE_RULES = {
    "version": 1,
    "allowSharedStorageWithdraw": False,
    "allowSharedStorageReturn": False,
    "allowSharedInfrastructureChanges": False,
    "allowExistingSurvivalFlight": False,
    "allowNativeMapTeleport": False,
    "manualControlPolicy": {"mode": "manual_input_priority"},
    "buildAreas": [],
    "verifiedTransportSites": [],
    "verifiedWaypoints": [],
    "verifiedSurvivalPortals": [],
    "survivalCommands": {"allow": [], "minimumIntervalSeconds": 10},
    "deathRecovery": {"enabled": False},
    "survivalGuard": {"enabled": False},
}
SAFE_AGENTS = """# ATM Star Pilot 共享服务器守则

这是分享版默认守则，不包含原使用者的坐标、个人授权或游戏进度。

- 先读本文件、README.md、RULES_GUIDE.zh-CN.md 与当前实例 config/atmpilot/server-rules.json，再读取实际状态。
- 现有共同基地和其他玩家设施禁止拆改、配置、接管或接线；同队领地和可交互状态不代表改造授权。
- 共享仓储取料须得到当前使用者/所有者授权，并在实际规则中明确记录；默认未授权。只正常取料与归还多余物品，不改升级、过滤、样板或机器设置。
- 新产线必须在隔开数个区块、确认没有他人设施或领地的独立区域从零建造。调查完整高度及所有自动机器工作范围，实际 buildAreas 默认空。
- 使用正常生存操作；不生成物品、不使用管理命令、不伪造任务进度或游戏能力。
- 默认人工输入优先；F7 控制面板、F8 紧急停止和用户明确停止仍优先。不得继承原使用者的持续全自动授权。
- 飞行、服务器旅行、传送台、自动恢复与生存守卫默认未授权；由当前使用者按自己的实例明确启用。
- 死亡时只恢复自己的墓碑和物品；不故意死亡测试，限制重试，保留检查点。中断后根据实际库存和世界重规划，避免重复投料或订单。
- 机器、配方、槽位、流体、能源和组件来自当前实际观察；旧快照、参考路线和已接受队列不能证明成功完成。
- 热更新和新增执行模块遵守相同区域/容器边界。支持范围不等于已跑通所有模组；项目仍在开发中，未完成 ATM 之星。
- 当前使用者的最新明确授权决定当前实例范围；原项目的自动控制或物资取用授权对本实例无效。
"""
SAFE_MCP = """# Replace these placeholder paths with paths on the recipient's own computer.
[mcp_servers.minecraft]
command = "python"
args = ["D:/ATM-Star-Pilot/mcp_server.py", "--session", "D:/Your-ATM10-Instance/config/atmpilot/session.json"]
startup_timeout_sec = 15
tool_timeout_sec = 60
"""


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def text(data: bytes) -> str:
    return data.decode("utf-8-sig")


def portable_copy(name: str, data: bytes, changes: list[dict]) -> bytes:
    """Normalize only packaging copies; never write back into project/runtime source files."""
    if name == "mcp_server.py":
        value, count = re.subn(r'DEFAULT_SESSION = pathlib\.Path\(r"[^"\r\n]+"\)',
                              'DEFAULT_SESSION = pathlib.Path("config/atmpilot/session.json")', text(data))
        if count != 1 and 'DEFAULT_SESSION = pathlib.Path("config/atmpilot/session.json")' not in value:
            raise RuntimeError("MCP default-path normalization needs review")
        changes.append({"file": name, "change": "Use a relative default session path; explicit --session recommended"})
        return value.encode()
    if name == "tools/deploy_runtime.py":
        value, count = re.subn(r'(?m)^        if pathlib\.Path\(r"[^"\r\n]+"\)\.is_file\(\):environment\["JAVA_HOME"\]=r"[^"\r\n]+"\r?\n', "", text(data))
        if count not in (0, 1):
            raise RuntimeError("Runtime-deployer path normalization needs review")
        if count:
            changes.append({"file": name, "change": "Inherit recipient JAVA_HOME instead of author JDK path"})
        return value.encode()
    if name == "tools/stage_core.py":
        value, count = re.subn(r'env = dict\(os\.environ, JAVA_HOME=r"[^"\r\n]+"\)', 'env = dict(os.environ)', text(data))
        if count not in (0, 1):
            raise RuntimeError("Core-stager path normalization needs review")
        if count:
            changes.append({"file": name, "change": "Inherit recipient JAVA_HOME instead of author JDK path"})
        return value.encode()
    if name == "tools/hotpatch.py":
        value = text(data)
        value, count = re.subn(r'default=pathlib\.Path\(r"[^"\r\n]+"\)',
                              'default=pathlib.Path(os.environ.get("JAVA_HOME", "."))', value)
        if count:
            value = value.replace("import json\n", "import json\nimport os\n", 1)
            changes.append({"file": name, "change": "Use recipient JAVA_HOME / explicit --jdk"})
        return value.encode()
    if name == "tools/import_pack.py":
        value, count = re.subn(r'PACK = pathlib\.Path\(r"[^"\r\n]+"\)',
                              'PACK = pathlib.Path(os.environ.get("ATM_PILOT_INSTANCE", "."))', text(data))
        if count:
            value = value.replace("import json\n", "import json\nimport os\n", 1)
            changes.append({"file": name, "change": "Read recipient instance path from ATM_PILOT_INSTANCE"})
        return value.encode()
    if name.endswith("CraftOwnershipTest.java"):
        value = text(data)
        uuid_pattern = r'UUID\.fromString\("[0-9a-fA-F-]{36}"\)'
        value, count = re.subn(uuid_pattern, 'UUID.fromString("00000000-0000-0000-0000-000000000001")', value)
        if count != 1:
            raise RuntimeError("Owner fixture normalization needs review")
        value, arrays = re.subn(r'\\"id\\":\[-?\d+,-?\d+,-?\d+,-?\d+\]', r'\"id\":[0,0,0,1]', value)
        if arrays != 1:
            raise RuntimeError("Owner integer fixture normalization needs review")
        value, invalid = re.subn(r'\.replace\("-?\d+","-?\d+\.5"\)',
                                 '.replace("[0,0,0,1]","[0.5,0,0,1]")', value)
        if invalid != 1 and '.replace("[0,0,0,1]","[0.5,0,0,1]")' not in value:
            raise RuntimeError("Invalid-owner fixture normalization needs review")
        changes.append({"file": name, "change": "Replace personal owner sample and matching signed integers with a fixed fictional fixture"})
        return value.encode()
    return data


def read_document(preferred: str, fallback: str) -> bytes:
    file = ROOT / preferred
    return (file if file.is_file() else ROOT / fallback).read_bytes()


def candidate_files() -> dict[str, bytes]:
    paths = [ROOT / name for name in (
        "build.gradle", "settings.gradle", "gradlew", "gradlew.bat", "LICENSE", ".gitignore",
        "mcp_server.py", "workflow_store.py", "work_queue.py", "test_mcp_server.py", "test_workflow_store.py", "test_work_queue.py", "test_stage_core.py",
    )]
    paths.extend((ROOT / "src").rglob("*"))
    paths.extend((ROOT / "gradle/wrapper").glob("*"))
    paths.extend((ROOT / "workflows").glob("*.json"))
    paths.extend((ROOT / "computer-programs").glob("*.lua"))
    paths.extend((ROOT / "skills/atm-star-pilot").rglob("*"))
    paths.extend(ROOT / "tools" / name for name in (
        "deploy_runtime.py", "stage_core.py", "hotpatch.py", "import_pack.py", "package_share.py", "run_queue.py",
        "hotpatch/AttachPatch.java", "hotpatch/HotPatchAgent.java", "share/install_client.py", "share/prepare_build.py",
    ))
    result = {}
    for path in paths:
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT).as_posix()
        if any(part in {"__pycache__", ".git", ".gradle", ".tools", "build", "runtime-evidence", "dist"} for part in path.relative_to(ROOT).parts):
            continue
        if path.suffix in {".pyc", ".log", ".png", ".jpg", ".webp", ".class"} or path.name == "session.json":
            continue
        result[relative] = path.read_bytes()
    return result


def scan_payload(name: str, data: bytes) -> None:
    """Only report categories/file names; never expose matched secret text."""
    value = data.decode("latin-1") if name.endswith(".class") else data.decode("utf-8", errors="ignore")
    checks = {
        "author-local absolute path": r'(?i)\b[A-Z]:[\\/](?:Users|Minecraft|Python\d+|jdk\d+)\b',
        "credential-like literal": r'(?i)["\'](?:access[_-]?token|api[_-]?key|password|token)["\']\s*[:=]\s*["\'][A-Za-z0-9_+./=-]{24,}',
        "known credential format": r'(?:gh[pousr]_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{24,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.)',
    }
    for category, pattern in checks.items():
        if re.search(pattern, value):
            raise RuntimeError(f"Sensitive-content review failed: {category} in {name}; value suppressed")
    for match in re.finditer(r'\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b', value):
        if match.group() != "00000000-0000-0000-0000-000000000001":
            raise RuntimeError(f"Non-fictional UUID literal in {name}; value suppressed")
    for match in re.finditer(r'(?<![0-9.])(?:\d{1,3}\.){3}\d{1,3}(?![0-9.])', value):
        address = match.group()
        if address != "127.0.0.1":
            raise RuntimeError(f"Non-loopback address in {name}; value suppressed")
    if name.endswith(".py"):
        ast.parse(data.decode("utf-8-sig"), filename=name)
    if name.endswith(".jar"):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if archive.testzip() is not None:
                raise RuntimeError(f"Invalid bundled JAR: {name}")
            if name == "gradle/wrapper/gradle-wrapper.jar":
                return  # Third-party wrapper is separately licensed; no instance content is included.
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                if Path(entry.filename).name == "session.json":
                    raise RuntimeError(f"Session entry in bundled JAR: {name}")
                scan_payload(name + "!" + entry.filename, archive.read(entry))


def write_archive(output: Path, files: dict[str, bytes], stamp: date, top: str) -> str:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".zip.tmp")
    try:
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, data in sorted(files.items()):
                entry = zipfile.ZipInfo(top + "/" + name, date_time=(stamp.year, stamp.month, stamp.day, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = ((0o755 if name == "gradlew" else 0o644) << 16)
                archive.writestr(entry, data)
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None or len(archive.infolist()) != len(files):
                raise RuntimeError("ZIP integrity check failed")
            for name, data in files.items():
                if sha(archive.read(top + "/" + name)) != sha(data):
                    raise RuntimeError("Archived-file checksum mismatch")
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    digest = sha(output.read_bytes())
    output.with_suffix(".zip.sha256").write_text(digest + "  " + output.name + "\n", encoding="ascii")
    return digest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--date", default=date.today().isoformat())
    parser.add_argument("--output", type=Path)
    parser.add_argument("--skill-output", type=Path)
    parser.add_argument("--expected-core-sha")
    parser.add_argument("--expected-runtime-sha")
    args = parser.parse_args()
    stamp = date.fromisoformat(args.date)
    output = args.output or ROOT / "dist" / f"atm-star-pilot-share-{stamp.isoformat()}.zip"
    skill_output = args.skill_output or output.parent / f"atm-star-pilot-skill-{stamp.isoformat()}.zip"
    if output.resolve() == skill_output.resolve():
        raise ValueError("Project and skill archives require different output paths")
    files = candidate_files()
    changes = []
    files = {name: portable_copy(name, data, changes) for name, data in files.items()}
    files["README.md"] = read_document("SHARE_README.zh-CN.md", "README.md")
    files["RULES_GUIDE.zh-CN.md"] = read_document("SHARE_RULES_GUIDE.zh-CN.md", "RULES_GUIDE.zh-CN.md")
    files["AGENTS.md"] = SAFE_AGENTS.encode()
    files["server-rules.example.json"] = (json.dumps(SAFE_RULES, ensure_ascii=False, indent=2) + "\n").encode()
    files["mcp-config.example.toml"] = SAFE_MCP.encode()
    properties = text((ROOT / "gradle.properties").read_bytes())
    properties = "\n".join(line for line in properties.splitlines() if not line.startswith("org.gradle.java.installations.paths=")) + "\n"
    files["gradle.properties"] = properties.encode()
    files[".gitignore"] += b"\ndist/\n__pycache__/\n*.pyc\nsession.json\nconfig/atmpilot/\n"
    changes.append({"file": "gradle.properties", "change": "Remove author-machine toolchain search paths; use recipient JDK 21"})
    changes.append({"file": "AGENTS.md / server-rules.example.json / mcp-config.example.toml / README.md", "change": "Generate independent sharing defaults and current capability/installation documentation"})
    with zipfile.ZipFile(io.BytesIO(files["gradle/wrapper/gradle-wrapper.jar"])) as wrapper:
        files["THIRD_PARTY/Gradle-wrapper-LICENSE.txt"] = wrapper.read("META-INF/LICENSE")
    files["THIRD_PARTY/NOTICE.txt"] = (
        "Project code: MIT, see LICENSE.\n"
        "Gradle wrapper: Apache License 2.0, see Gradle-wrapper-LICENSE.txt and wrapper source headers.\n"
        "JEI, NeoForge, Minecraft and ATM10 are not redistributed by this archive. Obtain compatible installations separately.\n"
    ).encode()
    artifacts = {}
    for name in (CORE_NAME, RUNTIME_NAME):
        built = ROOT / "build/libs" / name
        data = (built if built.is_file() else ROOT / "release" / name).read_bytes()
        expected = args.expected_core_sha if name == CORE_NAME else args.expected_runtime_sha
        if expected and sha(data) != expected.lower():
            raise RuntimeError("Precompiled artifact changed from the requested snapshot")
        files["release/" + name] = data
        artifacts[name] = {"sha256": sha(data), "bytes": len(data)}
    for name, data in files.items():
        scan_payload(name, data)
    skill_prefix = "skills/atm-star-pilot/"
    skill_files = {name[len(skill_prefix):]: data for name, data in files.items() if name.startswith(skill_prefix)}
    if "SKILL.md" not in skill_files or "agents/openai.yaml" not in skill_files:
        raise RuntimeError("The maintained ATM Star Pilot skill is missing from this snapshot")
    manifest = {
        "package": "ATM Star Pilot source + precompiled development snapshot",
        "date": stamp.isoformat(),
        "createdAtUtc": datetime.now(timezone.utc).isoformat(),
        "status": "Development framework; not an autonomous complete ATM Star walkthrough",
        "target": {"atm10": "8.2", "minecraft": "1.21.1", "neoforge": "21.1.251", "java": 21, "pythonMinimum": "3.10"},
        "artifacts": artifacts,
        "skill": {"name": "atm-star-pilot", "path": "skills/atm-star-pilot", "standaloneArchive": skill_output.name, "files": len(skill_files)},
        "packagingOnlyNormalizations": changes,
        "excluded": EXCLUDED,
        "files": [{"path": name, "bytes": len(data), "sha256": sha(data)} for name, data in sorted(files.items())],
    }
    files["SHARE_MANIFEST.json"] = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()
    scan_payload("SHARE_MANIFEST.json", files["SHARE_MANIFEST.json"])
    digest = write_archive(output, files, stamp, TOP)
    skill_digest = write_archive(skill_output, skill_files, stamp, "atm-star-pilot")
    print(json.dumps({"archive": str(output.resolve()), "entries": len(files), "bytes": output.stat().st_size,
                      "sha256": digest, "artifacts": artifacts, "staticPrivacyScan": "passed",
                      "skillArchive": str(skill_output.resolve()), "skillBytes": skill_output.stat().st_size, "skillSha256": skill_digest,
                      "zipReadAndChecksums": "passed", "gameStartedOrRestarted": False}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

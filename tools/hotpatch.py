"""Compile a small JDK agent and update stopped ATM Pilot method bodies without restarting Minecraft."""
from __future__ import annotations
import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT))
from mcp_server import Bridge, DEFAULT_SESSION


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("classes",nargs="+",help="ATM Pilot class names, e.g. WorldView MachineJob")
    parser.add_argument("--jdk",type=pathlib.Path,default=pathlib.Path(os.environ.get("JAVA_HOME", ".")))
    parser.add_argument("--session",type=pathlib.Path,default=DEFAULT_SESSION)
    parser.add_argument("--disconnected-ui",action="store_true",help="Narrow offline recovery patch for PilotHttp, ScreenAccess and OfflineUi only")
    args=parser.parse_args()
    bridge=Bridge(args.session)
    state=bridge.request("/state")
    offline_names={name.removeprefix("dev.atmpilot.") for name in args.classes}
    offline_ui=args.disconnected_ui and {"PilotHttp","ScreenAccess","OfflineUi"}.issubset(offline_names) and offline_names.issubset({"PilotHttp","ScreenAccess","OfflineUi","SneakControl","ReflexResumable","ManualInputPolicy"})
    if args.disconnected_ui and not offline_ui:
        raise RuntimeError("Offline patch is restricted to the connection UI classes")
    if not state.get("connected") and not offline_ui:
        raise RuntimeError("Enter the Minecraft world before patching")
    if state.get("controller",{}).get("state")=="running":
        raise RuntimeError("Stop and inspect the running job before patching")
    normalized={name.removeprefix("dev.atmpilot.") for name in args.classes}
    cursor_bootstrap=normalized=={"CursorReturn","PilotHttp"}
    carried_before=state.get("menu",{}).get("carried",{})
    if carried_before.get("count",0) and not cursor_bootstrap:
        raise RuntimeError("Return the carried stack before patching")
    session=json.loads(args.session.read_text(encoding="utf-8-sig"))
    names=[]
    for name in args.classes:
        if name.startswith("dev.atmpilot."):
            name=name[len("dev.atmpilot."):]
        if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*",name):
            raise ValueError("Only ATM Pilot classes can be patched")
        names.append("dev.atmpilot."+name)
    patch_id=uuid.uuid4().hex
    directory=ROOT/".tools"/"hotpatch"/patch_id
    classes=directory/"agent-classes"
    classes.mkdir(parents=True)
    sources=ROOT/"tools"/"hotpatch"
    agent_name="HotPatchAgent_"+patch_id
    generated=directory/(agent_name+".java")
    generated.write_text((sources/"HotPatchAgent.java").read_text(encoding="utf-8").replace("HotPatchAgent",agent_name),encoding="utf-8")
    subprocess.run([str(args.jdk/"bin"/"javac.exe"),"--release","21","--add-modules","jdk.attach",
                    "-d",str(classes),str(generated),str(sources/"AttachPatch.java")],check=True)
    manifest=directory/"MANIFEST.MF"
    manifest.write_text("Manifest-Version: 1.0\nAgent-Class: dev.atmpilot.tools."+agent_name+"\nCan-Redefine-Classes: true\n\n",encoding="utf-8")
    agent=directory/"atmpilot-hotpatch-agent.jar"
    subprocess.run([str(args.jdk/"bin"/"jar.exe"),"--create","--file",str(agent),"--manifest",str(manifest),"-C",str(classes),"."],check=True)
    result_file=directory/"result.properties"
    properties=["patchId="+patch_id,"result="+result_file.as_posix(),"classCount="+str(len(names))]
    for index,name in enumerate(names):
        source=ROOT/"build"/"classes"/"java"/"main"/pathlib.Path(*name.split("."))
        source=source.with_suffix(".class")
        data=source.read_bytes()
        copy=directory/(str(index)+".class")
        copy.write_bytes(data)
        properties.extend([f"class.{index}.name={name}",f"class.{index}.file={copy.as_posix()}"])
    request=directory/"request.properties"
    request.write_text("\n".join(properties)+"\n",encoding="utf-8")
    subprocess.run([str(args.jdk/"bin"/"java.exe"),"--add-modules","jdk.attach","-cp",str(classes),
                    "dev.atmpilot.tools.AttachPatch",str(session["pid"]),str(agent),str(request)],check=True,timeout=45)
    result=result_file.read_text(encoding="utf-8")
    print(result)
    if "success=true" not in result.splitlines():
        raise RuntimeError("Hot patch rejected; existing class definitions were retained")
    current=bridge.request("/state")
    if cursor_bootstrap and carried_before.get("count",0) and current.get("menu",{}).get("carried",{})!=carried_before:
        raise RuntimeError("Cursor changed during the narrow cursor-return endpoint bootstrap; inspect it")
    if json.loads(args.session.read_text(encoding="utf-8-sig"))["pid"]!=session["pid"]:
        raise RuntimeError("Game process changed during verification")
    print(json.dumps({"sameGameProcess":True,"connected":current.get("connected"),"evidence":str(directory)},ensure_ascii=True))


if __name__=="__main__":
    main()

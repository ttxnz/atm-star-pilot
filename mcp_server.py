"""ATM Pilot MCP server. Python 3.10+, standard library only, newline-delimited stdio."""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import json
import pathlib
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from workflow_store import WorkflowStore

DEFAULT_SESSION = pathlib.Path("config/atmpilot/session.json")
SUPPORTED_PROTOCOLS = {"2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"}


def schema(properties=None, required=()):
    return {"type": "object", "properties": properties or {}, "required": list(required), "additionalProperties": False}


STRING = {"type": "string"}
INTEGER = {"type": "integer"}
ACTION = {
    "type": "object", "required": ["type"], "additionalProperties": True,
    "properties": {"type": {"anyOf": [{"type": "string", "pattern": "^extension:[a-z0-9_]+$"}, {"type": "string", "enum": [
        "wait", "look", "select", "move", "walk", "walk_near", "mine", "use_block", "place",
        "use_item", "attack", "inventory", "close", "click", "transfer", "jei_transfer", "craft",
        "machine", "blueprint", "remember", "screen_click", "screen_key", "screen_text",
        "quest_open", "menu_button", "assert_inventory"
    ]}]}}
}


def tool(name, description, input_schema, readonly=True):
    return {"name": name, "description": description, "inputSchema": input_schema,
            "annotations": {"readOnlyHint": readonly, "destructiveHint": not readonly,
                            "idempotentHint": readonly, "openWorldHint": False}}


TOOLS = [
    tool("minecraft_vanilla_status", "Read the dedicated vanilla survival runner and exact world key. No game mutation. This mode requires only the client control mod and NeoForge 1.21.1; JEI is optional.", schema()),
    tool("minecraft_vanilla_start", "Start or explicitly resume the experimental zero-to-dragon vanilla survival mode in the exact confirmed dedicated world. Changes that world through normal survival interactions. No flight, teleport, commands, world editing, JEI, or server mod. Starting a fresh run requires empty inventory and vanilla content. Never use on the ATM/shared world. F8 or physical input pauses; resume reobserves inventory and does not replay pending clicks. Not yet end-to-end validated.", schema({"confirmWorld": STRING, "allowWorldChanges": {"type":"boolean"}, "resume": {"type":"boolean"}, "maxTicks": {"type":"integer", "minimum":1200, "maximum":3456000}}, ("confirmWorld", "allowWorldChanges")), False),
    tool("minecraft_vanilla_stop", "Pause the vanilla run, release keys and save its checkpoint. Does not erase the world or inventory.", schema(), False),
    tool("minecraft_vanilla_leave", "Leave vanilla mode after pausing it, allowing normal ATM workflows again. Does not start any workflow.", schema(), False),
    tool("minecraft_live", "Read compact live position, velocity, flight/ground state, exact nearby entity UUIDs and local-defense state. Prefer this for movement and targets; use inventory for item/menu details.", schema({"radius": {"type": "integer", "minimum": 1, "maximum": 64}})),
    tool("minecraft_status", "Read player position, health, inventory summary, JEI catalog readiness and current job. Works when the game is offline.", schema()),
    tool("minecraft_inventory", "Read actual inventory and the currently open menu. Slot numbers are menu indices, not screen coordinates. Empty slots are included. Re-observe after opening a new menu.", schema()),
    tool("minecraft_scan", "Read loaded nearby machines/containers and entities. Does not access unopened container contents.", schema({"radius": {"type": "integer", "minimum": 1, "maximum": 24}})),
    tool("minecraft_region", "Inspect a bounded loaded block region for area/production-line planning. Up to 32768 cells; reports unloaded cells and truncation. Optional blocks is a list of exact block IDs.",
         schema({**{k: INTEGER for k in ("minX", "maxX", "minY", "maxY", "minZ", "maxZ")}, "blocks": {"type": "array", "items": STRING}}, ("minX", "maxX", "minY", "maxY", "minZ", "maxZ"))),
    tool("minecraft_recipes", "Find live JEI producers for an item. Includes modded machine inputs, counts, item components, catalysts and recipe IDs. Recipe descriptions alone do not prove execution support.",
         schema({"item": STRING, "type": STRING, "offset": INTEGER, "limit": {"type": "integer", "minimum": 1, "maximum": 100}})),
    tool("minecraft_recipe", "Read one complete live recipe by its exact recipe ID.", schema({"id": STRING}, ("id",))),
    tool("minecraft_plan", "Expand a target into dependency steps and shortages using actual player inventory. Default target is ATM Star. This is a planning estimate; custom machine conditions still require inspection.",
         schema({"item": STRING, "count": {"type": "integer", "minimum": 1, "maximum": 1000000000000}, "components": STRING})),
    tool("minecraft_quests", "Read client-synchronised FTB quest titles, task requirements/progress and dependencies. This never marks quests complete.", schema({"filter": STRING, "offset": INTEGER, "limit": INTEGER})),
    tool("minecraft_screen", "Read game GUI dimensions and visible vanilla widget bounds/text. Custom machine widgets can be operated with screen_click and checked with screenshots.", schema()),
    tool("minecraft_screenshot", "Capture the Minecraft framebuffer as an MCP image. Coordinates for screen actions use GUI-scaled dimensions from minecraft_screen, not raw image pixels.", schema()),
    tool("minecraft_execute",
         "Queue one ordinary client action. Core: walk/walk_near/mine/use_block/place(x,y,z), use_block(expectMenu), move(keys,ticks), look(yaw,pitch), select(slot0..8), transfer(containerId,from,to,count1..64), click(containerId,slot,button,clickType), craft(recipe,count), machine(recipe,count,inputSlots,outputSlots,timeoutTicks), blueprint(blocks), quest_open(quest), screen_click/key/text. "
         "Hot modules: extension:open_backpack, extension:survey(bounds,surfaceY), extension:surface_scan(x,z,radius), extension:fly(x,y,z,cruiseY,hover), extension:flight_toggle(flying), extension:open_face(x,y,z,face), extension:use_sneaking(x,y,z,face,expectedBlock), extension:ae_open(x,y,z,face), extension:ae_inspect, extension:ae_withdraw(item,count,components), extension:ae_deposit(items), extension:ae_attach(x,y,z,face,item), extension:ae_clear_grid, extension:ae_native_inspect, extension:ae_encode(recipe), extension:ae_request(item,count,components,timeoutTicks), extension:menu_probe(item), extension:item_facts(item), extension:collect_output(item,count,slots,timeoutTicks), extension:water_source(x,y,z,source,stand), extension:bucket_ray, extension:fluix_batch(x,y,z,count), extension:charger_inspect(positions), extension:charger_batch(positions,count), extension:squeezer_inspect(x,y,z), extension:squeezer_press(x,y,z), extension:fluid_tank_inspect, extension:bee_spawning_inspect(item,x,y,z), extension:mek_recipe(recipe), extension:mek_batch(recipe,count,reagent), extension:botany_inspect(positions), extension:infusion_inspect(x,y,z), extension:infusion(x,y,z,lever,recipe), extension:titanium_inspect, extension:pipe_inspect(positions), extension:pipe_configure(x,y,z,side,extracting,stand), extension:travel(command,expected), extension:recovery_resume, extension:recovery_acknowledge, extension:safe_home(resume), extension:pad_travel(x,y,z), extension:portal_travel(name), extension:waypoints_inspect(dimension), extension:waypoint_add(name), extension:waypoint_travel(name), extension:chunks_open(chunks), extension:chunks_inspect(chunks), extension:chunks_claim_load(chunks), extension:bee_nest_inspect(positions), extension:bee_recipes(filter,limit), extension:bee_capture(entityId,uuid,expectedSpecies), extension:bee_watch. "
         "AE crafting and machine/pipe configuration require the own surveyed factory. Mek factory batches verify item/chemical requirements, stage inputs once and check actual outputs. Titanium inspection reads only the currently open native menu; pot/altar/pipe inspection reads visible synchronized state. Pipe configuration uses one normal wrench click only when needed and verifies the resulting arrow. "
         "Local controls: extension:animal_capture(entityId,uuid,expectedType) lands and follows the live UUID before single native shrink/bottle interactions; extension:combat(entityId,uuid,expectedType) performs bounded hostile-only melee; extension:live_inspect(radius) saves compact telemetry. Physical input holds persist across new jobs; only an explicitly user-authorized recovery_resume first step releases them. "
         "Travel permits only user-authorized normal server /home [name] and /back, waits for known local cooldowns and verifies arrival. No operator commands, spawning, invented abilities or arbitrary code execution. Inspect stopped recovery before explicitly resuming; never blindly replay interrupted production.",
         schema({"action": ACTION}, ("action",)), False),
    tool("minecraft_workflow", "Queue 1..4096 actions for fast local execution without per-click MCP round trips. Jobs stop on failure, manual input, low health or dimension change. Observe inventory/menu and exact recipe IDs first. Typical production workflow: close,walk_near,use_block(expectMenu),transfer fuel,craft/machine,assert_inventory,close.",
         schema({"steps": {"type": "array", "items": ACTION, "minItems": 1, "maxItems": 4096}}, ("steps",)), False),
    tool("minecraft_workflows", "List external JSON workflows, or preview a named workflow with parameters. Each call reads the latest file in config/atmpilot/workflows; game restart is unnecessary. Preview does not execute actions.",
         schema({"name": STRING, "parameters": {"type": "object"}})),
    tool("minecraft_run_workflow", "Load the latest named external JSON workflow, substitute data parameters, then queue a fixed snapshot for fast local execution. Inspect the preview and live world first. Editing the file does not alter an already running job. Stop and inspect before resubmitting an interrupted production job.",
         schema({"name": STRING, "parameters": {"type": "object"}}, ("name",)), False),
    tool("minecraft_job", "Read current asynchronous job status. Optional waitSeconds waits up to 30 seconds for completion/failure; does not imply success after timeout.",
         schema({"waitSeconds": {"type": "number", "minimum": 0, "maximum": 30}})),
    tool("minecraft_stop", "Immediately clear the queue and release controlled keys. Leaves any carried stack visible for recovery.", schema(), False),
    tool("minecraft_return_cursor", "Return an observed carried stack to an observed empty compatible player slot after a job stops. Sends one normal click; inspect inventory afterwards. Never targets machine slots or drops items.",
         schema({"containerId":INTEGER,"slot":INTEGER,"item":STRING,"count":{"type":"integer","minimum":1,"maximum":64}},("containerId","slot","item","count")),False),
    tool("minecraft_containers", "Read previously observed container snapshots with timestamps and locations. These are historical observations, not guaranteed current stock.", schema()),
    tool("minecraft_roadmap", "Read the personal ATM10 v8.2 recipe and multiblock reference extracted from the installed pack. Live JEI takes precedence if the server differs.", schema({"section": STRING, "name": STRING})),
]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        raise RuntimeError("Minecraft loopback transport must not redirect")


class Bridge:
    def __init__(self, session: pathlib.Path):
        self.session = session
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.ProxyHandler({}))
        self.workflows = WorkflowStore(session.parent / "workflows")

    def request(self, path, method="GET", body=None, binary=False):
        try:
            session = json.loads(self.session.read_text(encoding="utf-8-sig"))
        except FileNotFoundError:
            raise RuntimeError("Minecraft bridge is offline. Start the ATM10 instance with ATM Star Pilot installed.") from None
        url = urllib.parse.urlsplit(session["url"])
        if url.scheme != "http" or url.hostname != "127.0.0.1" or not url.port or url.path or url.username:
            raise RuntimeError("Invalid local bridge session endpoint")
        if not path.startswith("/") or path.startswith("//"):
            raise ValueError("Invalid endpoint path")
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(session["url"] + path, data=data, method=method,
            headers={"Authorization": "Bearer " + session["token"], "Content-Type": "application/json"})
        try:
            with self.opener.open(request, timeout=20) as response:
                payload = response.read(8_000_001)
                if len(payload) > 8_000_000:
                    raise RuntimeError("Bridge response too large; request a smaller region/page")
                return payload if binary else json.loads(payload)
        except urllib.error.HTTPError as error:
            try:
                message = json.loads(error.read(65536)).get("error", "Bridge request failed")
            except (ValueError, OSError):
                message = f"Bridge HTTP {error.code}"
            raise RuntimeError(message) from None
        except urllib.error.URLError:
            raise RuntimeError("Minecraft bridge is not responding. Start/restart the game; no mutation was automatically retried.") from None

    @staticmethod
    def query(path, arguments):
        return path + "?" + urllib.parse.urlencode(arguments)

    def call(self, name, args, cancelled):
        if name == "minecraft_vanilla_status":
            return self.request("/vanilla")
        if name in ("minecraft_vanilla_start", "minecraft_vanilla_stop", "minecraft_vanilla_leave"):
            return self.request("/vanilla/" + name.removeprefix("minecraft_vanilla_"), "POST", args)
        if name == "minecraft_live":
            radius = int(args.get("radius", 16))
            if not 1 <= radius <= 64:
                raise ValueError("live radius must be 1..64")
            return self.request(self.query("/live", {"radius": radius}))
        if name == "minecraft_workflows":
            return self.workflows.load(args["name"], args.get("parameters")) if args.get("name") else self.workflows.list()
        if name == "minecraft_run_workflow":
            workflow = self.workflows.load(args["name"], args.get("parameters"))
            result = self.request("/jobs", "POST", {"steps": workflow["steps"]})
            return {**result, "workflow": workflow["name"], "workflowSha256": workflow["sha256"]}
        if name == "minecraft_status":
            try:
                state = self.request("/state")
            except (RuntimeError, KeyError, ValueError) as error:
                return {"bridgeAvailable": False, "connected": False, "reason": str(error)}
            state["inventory"] = [s for s in state.get("inventory", []) if s.get("count", 0)]
            if "menu" in state:
                state["menu"] = {k: v for k, v in state["menu"].items() if k != "slots"}
            return state
        if name == "minecraft_inventory":
            state = self.request("/state")
            return {"player": state.get("player"), "inventory": state.get("inventory"), "menu": state.get("menu")}
        if name == "minecraft_scan":
            radius = int(args.get("radius", 16))
            if not 1 <= radius <= 24:
                raise ValueError("radius must be 1..24")
            return {"blocks": self.request(self.query("/scan", {"radius": radius})),
                    "entities": self.request(self.query("/entities", {"radius": radius}))}
        if name == "minecraft_region":
            query = {k: args[k] for k in ("minX", "maxX", "minY", "maxY", "minZ", "maxZ")}
            if args.get("blocks"):
                query["blocks"] = ",".join(args["blocks"])
            return self.request(self.query("/region", query))
        endpoints = {"minecraft_recipes": "/recipes", "minecraft_recipe": "/recipe", "minecraft_plan": "/plan",
                     "minecraft_quests": "/quests", "minecraft_screen": "/screen", "minecraft_containers": "/containers"}
        if name in endpoints:
            return self.request(self.query(endpoints[name], args))
        if name == "minecraft_screenshot":
            return {"_image": base64.b64encode(self.request("/screenshot", binary=True)).decode("ascii")}
        if name == "minecraft_execute":
            return self.request("/jobs", "POST", args["action"])
        if name == "minecraft_workflow":
            steps = args["steps"]
            if not 1 <= len(steps) <= 4096:
                raise ValueError("workflow requires 1..4096 steps")
            return self.request("/jobs", "POST", {"steps": steps})
        if name == "minecraft_stop":
            return self.request("/stop", "POST", {})
        if name == "minecraft_return_cursor":
            return self.request("/cursor/return", "POST", args)
        if name == "minecraft_job":
            duration = float(args.get("waitSeconds", 0))
            if not 0 <= duration <= 30:
                raise ValueError("waitSeconds must be 0..30")
            deadline = time.monotonic() + duration
            while True:
                state = self.request("/job")
                if state.get("state") != "running" or time.monotonic() >= deadline:
                    return state
                if cancelled.wait(min(0.3, max(0, deadline - time.monotonic()))):
                    raise RuntimeError("MCP wait cancelled; running game job was not changed")
        if name == "minecraft_roadmap":
            roadmap = self.request("/roadmap")
            if args.get("section"):
                section = roadmap.get(args["section"])
                if section is None:
                    raise ValueError("Unknown roadmap section")
                if args.get("name") and isinstance(section, list):
                    return [entry for entry in section if args["name"].lower() in str(entry.get("name", "")).lower()]
                return section
            return {k: (len(v) if k == "structures" else v) for k, v in roadmap.items()}
        raise ValueError("Unknown tool")


class Server:
    def __init__(self, bridge, output=None):
        self.bridge = bridge
        self.output = output or sys.stdout
        self.lock = threading.Lock()
        self.pending = {}
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="minecraft-mcp")

    def write(self, message):
        with self.lock:
            self.output.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
            self.output.flush()

    def result(self, request_id, value):
        self.write({"jsonrpc": "2.0", "id": request_id, "result": value})

    def error(self, request_id, code, message):
        self.write({"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}})

    def execute(self, request_id, params, cancelled):
        try:
            name = params["name"]
            if name not in {t["name"] for t in TOOLS}:
                raise ValueError("Unknown tool")
            value = self.bridge.call(name, params.get("arguments", {}), cancelled)
            if isinstance(value, dict) and "_image" in value:
                result = {"content": [{"type": "image", "data": value["_image"], "mimeType": "image/png"}]}
            else:
                result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False, separators=(",", ":"))}]}
            if not cancelled.is_set():
                self.result(request_id, result)
        except Exception as error:
            if not cancelled.is_set():
                self.result(request_id, {"isError": True, "content": [{"type": "text", "text": str(error)}]})
        finally:
            self.pending.pop(request_id, None)

    def handle(self, request):
        request_id = request.get("id")
        method = request.get("method", "")
        params = request.get("params", {})
        if method == "notifications/cancelled":
            event = self.pending.get(params.get("requestId"))
            if event:
                event.set()
            return
        if "id" not in request:
            return
        if method == "initialize":
            requested = params.get("protocolVersion", "2025-11-25")
            self.result(request_id, {"protocolVersion": requested if requested in SUPPORTED_PROTOCOLS else "2025-11-25",
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "atm-star-pilot", "version": "0.1.0"},
                "instructions": "Read live state/recipes before mutating. Queue bulk workflows for speed. Ordinary survival interactions only. A queued/accepted job is not proof of completion: inspect minecraft_job and resulting inventory/world/quests. Stop on unexpected state. F8 or physical player input overrides automation."})
        elif method == "ping":
            self.result(request_id, {})
        elif method == "tools/list":
            self.result(request_id, {"tools": TOOLS})
        elif method == "tools/call":
            cancelled = threading.Event()
            self.pending[request_id] = cancelled
            self.pool.submit(self.execute, request_id, params, cancelled)
        else:
            self.error(request_id, -32601, "Method not found")

    def run(self, input_stream=None):
        for line in input_stream or sys.stdin:
            if len(line) > 4_000_000:
                self.error(None, -32600, "Request too large")
                continue
            try:
                request = json.loads(line)
                if not isinstance(request, dict) or request.get("jsonrpc") != "2.0":
                    raise ValueError("Invalid JSON-RPC request")
                self.handle(request)
            except (ValueError, TypeError, KeyError) as error:
                self.error(None, -32700, str(error))
        self.pool.shutdown(wait=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--session", type=pathlib.Path, default=DEFAULT_SESSION)
    args = parser.parse_args()
    if hasattr(sys.stdin, "reconfigure"):
        sys.stdin.reconfigure(encoding="utf-8")
        sys.stdout.reconfigure(encoding="utf-8")
    Server(Bridge(args.session)).run()


if __name__ == "__main__":
    main()

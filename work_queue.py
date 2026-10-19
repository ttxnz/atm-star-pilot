"""Resident, checkpointed handoff between prepared Minecraft workflows. No LLM or command evaluation."""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import time
import uuid
from datetime import datetime, timezone


def save_json(path, value):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def fingerprint(stage):
    return hashlib.sha256(json.dumps(stage, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def check_world(state, checks):
    if not state.get("connected") or not state.get("player", {}).get("alive"):
        raise RuntimeError("Player is offline or dead; retain checkpoint")
    player = state["player"]
    if player.get("health", 0) <= max(4, checks.get("minHealth", 12)):
        raise RuntimeError("Health requires the client's local survival response")
    for name in ("survivalReflex", "deathRecovery", "recovery"):
        guard = state.get(name, {})
        if guard.get("manualHold") or guard.get("pendingEscape") or guard.get("state") in ("recovering", "recovered_needs_replan"):
            raise RuntimeError("Explicit stop or recovery checkpoint requires replan")
    if state.get("menu", {}).get("carried", {}).get("count", 0):
        raise RuntimeError("Cursor contains items; retain checkpoint")
    for condition, actual in (("menuClass", state.get("menu", {}).get("class")), ("menuId", state.get("menu", {}).get("id")), ("screenClass", player.get("screen"))):
        if condition in checks and checks[condition] != actual:
            raise RuntimeError("Prepared workflow GUI changed: " + condition)
    if checks.get("dimension") and player.get("dimension") != checks["dimension"]:
        raise RuntimeError("Prepared workflow dimension changed")
    if "position" in checks:
        point = checks["position"]
        distance = sum((player[axis] - point[axis]) ** 2 for axis in ("x", "y", "z")) ** .5
        if distance > point.get("radius", 2):
            raise RuntimeError("Prepared workflow starting position changed")
    stock = {}
    for stack in state.get("inventory", []):
        if stack.get("material", {}).get("components", ""):
            continue
        stock[stack.get("id")] = stock.get(stack.get("id"), 0) + stack.get("count", 0)
    for item, count in checks.get("items", {}).items():
        if stock.get(item, 0) < count:
            raise RuntimeError("Prepared workflow lacks " + item)
    for item, count in checks.get("itemsAtMost", {}).items():
        if stock.get(item, 0) > count:
            raise RuntimeError("Workflow result differs for " + item)


class QueueRunner:
    def __init__(self, bridge, plan, checkpoint):
        self.bridge, self.plan, self.checkpoint = bridge, pathlib.Path(plan), pathlib.Path(checkpoint)
        self.state = json.loads(self.checkpoint.read_text(encoding="utf-8-sig")) if self.checkpoint.exists() else {}

    def update(self, **values):
        if any(self.state.get(key) != value for key, value in values.items()):
            self.state.update(values)
            self.state["updatedAt"] = datetime.now(timezone.utc).isoformat()
            save_json(self.checkpoint, self.state)

    def pause(self, reason):
        self.update(status="needs_replan", reason=str(reason))

    def tick(self):
        if self.state.get("status") == "needs_replan":
            return
        try:
            self.advance()
        except (RuntimeError, ValueError, OSError, KeyError, TypeError) as error:
            self.pause(error)

    def advance(self):
        plan = json.loads(self.plan.read_text(encoding="utf-8-sig"))
        if not plan.get("enabled", False):
            self.update(status="paused_by_plan")
            return
        stages = plan["stages"]
        ids = [stage["id"] for stage in stages]
        if len(set(ids)) != len(ids) or any(not stage.get("steps") or len(stage["steps"]) > 4096 for stage in stages):
            raise ValueError("Unique stage IDs and 1..4096 prepared actions are required")
        if "generation" not in self.state:
            self.update(generation=plan["generation"], completed=[], fingerprints={}, expectedJob=plan["afterJob"], status="waiting")
        if self.state["generation"] != plan["generation"]:
            raise RuntimeError("Queue generation changed; inspect the old checkpoint before replacing it")
        if any(stage_id not in ids for stage_id in self.state.get("fingerprints", {})):
            raise RuntimeError("An accepted stage was removed; retain its checkpoint")
        for stage in stages:
            previous = self.state.get("fingerprints", {}).get(stage["id"])
            if previous and previous != fingerprint(stage):
                raise RuntimeError("An accepted stage was edited: " + stage["id"])
        active = self.state.get("inflight")
        if active and not active.get("job"):
            raise RuntimeError("Previous dispatch has uncertain outcome; never replay it automatically")
        job = self.bridge.request("/job")
        expected = active["job"] if active else self.state["expectedJob"]
        if job.get("id", "") != expected:
            raise RuntimeError("Another client job took over; inspect before continuing")
        if job.get("state") == "running":
            self.update(status="running" if active else "waiting_for_baseline")
            return
        if job.get("state") != "completed":
            raise RuntimeError("Client job did not complete: " + str(job.get("message", "")))
        if active:
            stage = next(stage for stage in stages if stage["id"] == active["stage"])
            check_world(self.bridge.request("/state"), stage.get("after", {}))
            done = self.state["completed"] + [stage["id"]]
            self.update(completed=done, expectedJob=job["id"], inflight=None, status="ready", lastCompletedAt=time.time())
        pending = next((stage for stage in stages if stage["id"] not in self.state["completed"]), None)
        if pending is None:
            self.update(status="awaiting_plan", reason="Append prepared stages to this generation; the resident watcher remains ready")
            return
        check_world(self.bridge.request("/state"), pending.get("before", {}))
        hashes = {**self.state["fingerprints"], pending["id"]: fingerprint(pending)}
        self.update(status="dispatching", fingerprints=hashes, inflight={"stage": pending["id"], "job": None, "submittedAt": time.time()})
        # Persist intent first. A timeout/crash after POST cannot cause a duplicate craft or placement.
        accepted = self.bridge.request("/jobs", "POST", {"steps": pending["steps"]})
        if not accepted.get("id"):
            raise RuntimeError("Client did not return a job ID; inspect before retrying")
        gap = max(0, time.time() - self.state["lastCompletedAt"]) if "lastCompletedAt" in self.state else None
        self.update(status="running", inflight={**self.state["inflight"], "job": accepted["id"]}, lastDispatchGapSeconds=gap, reason=pending.get("name", pending["id"]))

"""Reload workflow data for each invocation; no Java rebuild or script evaluation."""
from __future__ import annotations

import hashlib
import json
import pathlib
import re


NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}\Z")
ACTION_TYPES = {
    "wait", "look", "select", "move", "walk", "walk_near", "mine", "use_block", "place",
    "use_item", "attack", "inventory", "close", "click", "transfer", "jei_transfer", "craft",
    "machine", "blueprint", "remember", "screen_click", "screen_key", "screen_text",
    "quest_open", "menu_button", "assert_inventory",
}


def invalid_number(value):
    raise ValueError(f"Non-finite JSON number: {value}")


class WorkflowStore:
    def __init__(self, directory: pathlib.Path):
        self.directory = directory

    def read(self, name):
        if not isinstance(name, str) or not NAME.fullmatch(name):
            raise ValueError("Workflow name must be 1..64 letters, digits, underscores or hyphens")
        root = self.directory.resolve()
        file = (root / (name + ".json")).resolve()
        if file.parent != root:
            raise ValueError("Workflow must be inside its configured directory")
        with file.open("rb") as stream:
            data = stream.read(2_000_001)
        if len(data) > 2_000_000:
            raise ValueError("Workflow exceeds 2 MB")
        document = json.loads(data.decode("utf-8-sig"), parse_constant=invalid_number)
        if not isinstance(document, dict) or document.get("version") != 1:
            raise ValueError("Workflow requires version: 1")
        return document, hashlib.sha256(data).hexdigest()

    def list(self):
        result = []
        for file in sorted(self.directory.glob("*.json")):
            try:
                document, digest = self.read(file.stem)
                result.append({"name": file.stem, "description": document.get("description", ""),
                               "sha256": digest})
            except (OSError, ValueError) as error:
                result.append({"name": file.stem, "error": str(error)})
        return {"directory": str(self.directory), "workflows": result}

    def load(self, name, parameters=None):
        document, digest = self.read(name)
        defaults = document.get("defaults", {})
        parameters = {} if parameters is None else parameters
        if not isinstance(defaults, dict) or not isinstance(parameters, dict):
            raise ValueError("defaults and parameters must be objects")
        values = {**defaults, **parameters}
        used = set()

        def expand(node, depth=0):
            if depth > 32:
                raise ValueError("Workflow nesting exceeds 32 levels")
            if isinstance(node, dict):
                if set(node) == {"$param"}:
                    key = node["$param"]
                    if not isinstance(key, str) or key not in values:
                        raise ValueError(f"Missing workflow parameter: {key}")
                    used.add(key)
                    # Values are data, never expanded recursively as executable templates.
                    return values[key]
                return {key: expand(value, depth + 1) for key, value in node.items()}
            if isinstance(node, list):
                return [expand(value, depth + 1) for value in node]
            return node

        steps = expand(document.get("steps"))
        if not isinstance(steps, list) or not 1 <= len(steps) <= 4096:
            raise ValueError("Workflow requires 1..4096 steps")
        for step in steps:
            action_type = step.get("type") if isinstance(step, dict) else None
            if not isinstance(action_type, str) or (action_type not in ACTION_TYPES and not re.fullmatch(r"extension:[a-z0-9_]{1,64}", action_type)):
                raise ValueError("Workflow contains an unknown action type")
        unknown = set(parameters) - used
        if unknown:
            raise ValueError("Unused workflow parameters: " + ", ".join(sorted(unknown)))
        encoded = json.dumps({"steps": steps}, ensure_ascii=False, allow_nan=False).encode("utf-8")
        if len(encoded) > 2_000_000:
            raise ValueError("Expanded workflow exceeds 2 MB")
        return {"name": name, "description": document.get("description", ""),
                "sha256": digest, "steps": steps}

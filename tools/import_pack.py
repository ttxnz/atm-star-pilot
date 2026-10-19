"""Extract recipe/structure facts from a personal installed ATM10 pack; never execute pack JavaScript."""
from __future__ import annotations
import ast
import collections
import hashlib
import json
import os
import pathlib
import re

PACK = pathlib.Path(os.environ.get("ATM_PILOT_INSTANCE", "."))
PROJECT = pathlib.Path(__file__).resolve().parents[1]


def argument(text, marker):
    start = text.find(marker)
    if start < 0:
        raise ValueError("No " + marker)
    start += len(marker)
    depth = 1
    quote = None
    escaped = False
    for index in range(start, len(text)):
        ch = text[index]
        if quote:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = None
        elif ch in "'\"`":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[start:index]
    raise ValueError("Unterminated " + marker)


def material(text):
    match = re.fullmatch(r"(?:(\d+)x\s+)?([a-z0-9_.-]+:[a-z0-9_./-]+)(\[.*\])?", text, re.S)
    if not match:
        return {"raw": text, "unresolved": True}
    return {"item": match[2], "count": int(match[1] or 1), "componentPredicate": match[3] or ""}


def recipes():
    file = PACK / "kubejs/server_scripts/modpack/runic_multis/recipes/star_altar.js"
    text = file.read_text(encoding="utf-8-sig")
    result = []
    for block in re.split(r"(?=\s*//)", text):
        if ".star_altar(" not in block or ".itemOut(" not in block or "for (" in block:
            continue
        title = re.search(r"//([^\r\n]+)", block)
        inputs = [material(m[2]) for m in re.finditer(r"\.itemIn\((['\"`])(.*?)\1\)", block, re.S)]
        outputs = [material(m[2]) for m in re.finditer(r"\.itemOut\((['\"`])(.*?)\1\)", block, re.S)]
        if inputs and outputs:
            result.append({"name": title[1].strip() if title else "star_altar", "inputs": inputs, "outputs": outputs,
                           "source": str(file.relative_to(PACK)).replace("\\", "/")})
    return result


def structures():
    found, skipped = [], []
    root = PACK / "kubejs/server_scripts/mods/modular_machinery"
    for file in sorted(root.rglob("*.js")):
        text = file.read_text(encoding="utf-8-sig")
        if ".pattern(" not in text:
            continue
        try:
            raw = argument(text, ".pattern(").strip().rstrip(",")
            raw = re.sub(r"//[^\r\n]*", "", raw)
            pattern = ast.literal_eval(raw)
            if not isinstance(pattern, list) or not pattern or not all(isinstance(layer, list) for layer in pattern):
                raise ValueError("Variable or unsupported pattern")
            widths = {len(row) for layer in pattern for row in layer}
            depths = {len(layer) for layer in pattern}
            if len(widths) != 1 or len(depths) != 1 or not all(isinstance(row, str) for layer in pattern for row in layer):
                raise ValueError("Non-rectangular pattern")
            keys_raw = argument(text, ".keys(")
            keys = {}
            for match in re.finditer(r"['\"](.)['\"]\s*:\s*(['\"])([^'\"\r\n]+)\2", keys_raw):
                keys[match[1]] = [match[3]]
            for match in re.finditer(r"['\"](.)['\"]\s*:\s*(\[[^\]]*\])", keys_raw, re.S):
                try:
                    choices = ast.literal_eval(match[2])
                    if all(isinstance(choice, str) for choice in choices):
                        keys[match[1]] = choices
                except (ValueError, SyntaxError):
                    pass
            name = re.search(r"\.create\(['\"]([^'\"]+)['\"]\)", text)
            counts = collections.Counter(ch for layer in pattern for row in layer for ch in row if ch != " ")
            unresolved = sorted(set(counts) - set(keys))
            found.append({"name": name[1] if name else file.stem, "size": [next(iter(widths)), len(pattern), next(iter(depths))],
                          "patternYZX": pattern, "choices": keys, "symbolCounts": dict(counts),
                          "unresolvedSymbols": unresolved, "controllerSymbol": "m" if "m" in unresolved else None,
                          "source": str(file.relative_to(PACK)).replace("\\", "/"),
                          "sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
                          "note": "Reference template. Resolve controller components, port choices, orientation and any block-state conditions before building."})
        except (ValueError, SyntaxError, TypeError) as error:
            skipped.append({"file": str(file.relative_to(PACK)).replace("\\", "/"), "reason": str(error)})
    return found, skipped


def main():
    templates, skipped = structures()
    data = {"pack": "ATM10", "version": "8.2", "minecraft": "1.21.1", "neoforge": "21.1.251",
            "authority": "Personal installed pack snapshot; live server-synchronised JEI has precedence.",
            "starRoutes": recipes(), "structures": templates, "unparsedStructures": skipped,
            "executionStrategy": [
                "Index live recipes and quest progress, then survey existing resource/energy/storage infrastructure.",
                "Use dependency plans to reserve ingredients and select a concrete production route.",
                "Resolve machine menus, ports, components and power/fluid supply before dispatching a batch.",
                "Build repeated layouts with blueprint jobs; use bulk workflows for routine manufacture.",
                "Verify outputs and quest progress, then replan shortages; acquire the actual ATM Star in inventory."
            ]}
    output = PROJECT / "src/main/resources/atmpilot/atm10-v8.2.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"starRoutes": len(data["starRoutes"]), "structures": len(templates), "unparsed": len(skipped)}))


if __name__ == "__main__":
    main()

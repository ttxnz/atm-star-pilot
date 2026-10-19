"""Operate the world-bound vanilla runner without depending on MCP tool refresh."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from mcp_server import Bridge


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", required=True, type=Path)
    parser.add_argument("action", choices=("status", "start", "resume", "stop", "leave"))
    parser.add_argument("--confirm-world", help="Exact worldKey from status; authorizes changes in that world")
    args = parser.parse_args()
    bridge = Bridge(args.session)
    if args.action == "status":
        result = bridge.request("/vanilla")
    elif args.action in ("start", "resume"):
        if not args.confirm_world:
            parser.error("start/resume needs --confirm-world with the exact observed worldKey")
        result = bridge.request("/vanilla/start", "POST", {
            "confirmWorld": args.confirm_world, "allowWorldChanges": True,
            "resume": args.action == "resume",
        })
    else:
        result = bridge.request("/vanilla/" + args.action, "POST", {})
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

"""Compare read-only full state and compact live telemetry on the running client."""
from __future__ import annotations
import argparse
import json
import pathlib
import statistics
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from mcp_server import Bridge, DEFAULT_SESSION

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=8)
    args = parser.parse_args()
    if not 1 <= args.samples <= 50:
        parser.error("samples must be 1..50")
    bridge = Bridge(DEFAULT_SESSION)
    results = {"state": [], "live": []}
    for _ in range(args.samples):
        for kind, path in (("state", "/state"), ("live", "/live?radius=16")):
            started = time.perf_counter()
            value = bridge.request(path)
            results[kind].append({"milliseconds": (time.perf_counter()-started)*1000,
                                  "jsonBytes": len(json.dumps(value, ensure_ascii=False).encode("utf-8"))})
    summary = {kind: {"samples": len(rows), "medianMs": statistics.median(r["milliseconds"] for r in rows),
                      "medianJsonBytes": statistics.median(r["jsonBytes"] for r in rows)} for kind, rows in results.items()}
    report = {"summary": summary, "samples": results}
    (ROOT / "runtime-evidence" / "realtime-telemetry-benchmark.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(summary))

if __name__ == "__main__":
    main()

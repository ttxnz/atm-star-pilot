"""Run the prepared Minecraft work queue locally; no game restart and no background model required."""
from __future__ import annotations

import argparse
import os
import pathlib
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from mcp_server import Bridge, DEFAULT_SESSION
from work_queue import QueueRunner


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", type=pathlib.Path, default=DEFAULT_SESSION)
    parser.add_argument("--plan", type=pathlib.Path)
    parser.add_argument("--checkpoint", type=pathlib.Path)
    parser.add_argument("--once", action="store_true")
    args = parser.parse_args()
    plan = args.plan or args.session.parent / "work-queue.json"
    checkpoint = args.checkpoint or args.session.parent / "work-queue-state.json"
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    with checkpoint.with_suffix(".lock").open("a+b") as lock:
        lock.seek(0);lock.write(b"0");lock.flush();lock.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        runner = QueueRunner(Bridge(args.session), plan, checkpoint)
        runner.update(processPid=os.getpid())
        while True:
            runner.tick()
            if args.once:
                print(checkpoint.read_text(encoding="utf-8"));return
            time.sleep(.2 if runner.state.get("status") != "needs_replan" else 1)


if __name__ == "__main__":
    main()

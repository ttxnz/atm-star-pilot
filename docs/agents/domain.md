# Domain docs

Single-context Java client automation project. Read `AGENTS.md`, the instance's actual `config/atmpilot/server-rules.json`, and current `PROGRESS.md` checkpoints before acting.
If present, also read `CONTEXT.md` and relevant `docs/adr/` decisions. If absent, proceed without creating placeholder domain docs.

The core runs in the Minecraft JVM. Hot runtime modules are reloaded per job and may change schema; core instrumentation may replace method bodies only. Normal survival actions, actual synchronized state, own surveyed construction bounds, and explicit emergency stop remain mandatory.
